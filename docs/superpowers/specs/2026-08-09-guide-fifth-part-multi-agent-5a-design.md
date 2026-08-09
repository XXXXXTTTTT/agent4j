# 第五篇 5A：Multi-Agent Handoff 与有界子运行设计

## 章节边界

路线图把本里程碑标为第五篇第 17 章 `Multi-Agent`；教程源码
`tmp/ai-agent-guide-reference/chapters/ch10-multi-agent.html` 的页面内容包含编排者、Handoff、
Fork/Fresh、独立验证、预算和可观测性。本规格只吸收强类型通信、受限移交和独立子运行实践，
继续使用 Agent4J 自研 `StateGraph`，不引入 LangGraph、LangChain4j 或厂商 Agent SDK。

## 目标与非目标

5A 在现有 `GraphRegistry` 和 `StateGraph` 之上增加 `com.agent.core.multiagent`：

- Agent 必须先以不可变 `AgentDescriptor` 注册，精确声明图标识、可读状态键、拥有的输出状态键
  和允许移交的目标。
- Handoff 只接受结构化 `AgentHandoff`，目标必须位于来源 Agent 的白名单；不解析自然语言中的
  Agent 名称，也不做大小写、别名或相似度匹配。
- 上下文传递由 `HandoffContextMode.FORK` 或 `HandoffContextMode.FRESH` 明确选择。
- 子运行拥有独立 `childRunId`、虚拟线程、超时和结构化 Trace；父运行标识始终保留。
- 子状态合并前校验状态键所有权、只读输入未被修改、输出完整性和冲突。
- 深度、剩余 Handoff 次数、访问链和单次超时共同阻止无限递归与级联失控。

5A 不实现自然语言自主选择目标、并行写同一工作树、跨进程 A2A、远程 Agent、子运行 HITL 恢复、
数据库子 Checkpoint 或 Web Agent Profile。第 18 章再补子图拓扑验证；第六篇再提供只读 Profile
查询 API；第七篇才把 Handoff 接入 Coder→Ops→Reviewer 产品图。

## 强类型领域协议

### Agent 描述与目录

```java
public record AgentDescriptor(
        String agentId,
        String graphId,
        Set<String> readableStateKeys,
        Set<String> ownedStateKeys,
        Set<String> handoffTargets) {}

public final class AgentCatalog {
    public AgentCatalog(List<AgentDescriptor> descriptors);
    public List<AgentDescriptor> list();
    public AgentDescriptor require(String agentId);
}
```

`agentId` 和 `graphId` 必须是非空精确文本；所有状态键必须非空且集合内部唯一，
`readableStateKeys` 与 `ownedStateKeys` 不得重叠。目录构造时一次性校验 Agent ID 唯一、所有
`handoffTargets` 均已注册、Agent 不得移交给自身。目录发布稳定不可变快照。

### Handoff 请求与执行上下文

```java
public enum HandoffContextMode { FORK, FRESH }

public record AgentHandoff(
        UUID taskId,
        String fromAgent,
        String toAgent,
        String content,
        HandoffContextMode contextMode,
        Set<String> requestedOutputKeys,
        Duration timeout) {}

public record HandoffExecutionContext(
        int currentDepth,
        int maxDepth,
        int remainingHandoffs,
        List<String> visitedAgents) {
    public static HandoffExecutionContext root(
            String rootAgent, int maxDepth, int maxHandoffs);
    public HandoffExecutionContext descend(String toAgent);
}
```

`content` 是给目标 Agent 的明确任务正文，不作为 Agent ID 或状态键来源。`timeout` 必须大于 0 且
不超过 10 分钟。`requestedOutputKeys` 必须非空，并且全部属于目标 Agent 的 `ownedStateKeys`。
`HandoffExecutionContext` 要求 `currentDepth >= 0`、`maxDepth > 0`、`remainingHandoffs >= 0`，
访问链非空且末项必须是当前来源 Agent。执行前拒绝深度耗尽、次数耗尽、来源不等于访问链末项、
目标已在访问链中等情况；不对访问链名称做推断。

### 上下文组装

子运行变量只包含目标 Agent 明确声明的 `readableStateKeys`，缺失键立即抛
`AgentHandoffStateException`。两种模式的消息语义固定为：

- `FORK`：复制父状态全部 `messages`，再追加 `ChatMessage.user(content)`。
- `FRESH`：不复制任何父消息，只创建 `List.of(ChatMessage.user(content))`。

父状态的 `trace` 不传入子运行；子运行从空 trace 开始。两种模式都不复制未声明变量，因此
`FORK` 继承对话语境但不绕过最小状态权限，`FRESH` 通过 `content` 和显式只读输入得到完整 briefing。

### 结果与合并

```java
public record AgentHandoffResult(
        UUID taskId,
        UUID parentRunId,
        UUID childRunId,
        String fromAgent,
        String toAgent,
        AgentState childState,
        AgentState mergedParentState,
        HandoffExecutionContext childContext,
        Duration elapsed) {}
```

目标图正常完成后按以下顺序校验：

1. 所有传入的 `readableStateKeys` 必须仍存在且值未改变。
2. 子状态新增或改变的变量只能属于目标 Agent 的 `ownedStateKeys`。
3. 每个 `requestedOutputKeys` 必须存在于子状态。
4. 父状态不存在该键时写入；父状态已有相同值时保持；已有不同值时抛
   `AgentStateMergeException`，不做静默覆盖。
5. 子运行消息和 trace 不并入父状态；父状态只追加一条
   `handoff:<taskId>:<toAgent>:<childRunId>` trace 记录。

任一校验失败时父状态保持原实例语义，不产生部分合并结果。

## 子运行与 Trace

```java
public final class AgentHandoffExecutor implements AutoCloseable {
    public AgentHandoffExecutor(
            AgentCatalog catalog,
            GraphRegistry graphRegistry,
            AgentHandoffEventPublisher eventPublisher);

    public CompletableFuture<AgentHandoffResult> execute(
            UUID parentRunId,
            AgentState parentState,
            AgentHandoff handoff,
            HandoffExecutionContext context);
}
```

执行器使用 `Executors.newThreadPerTaskExecutor(Thread.ofVirtual()...)`。每次执行生成一个新的
`childRunId`，用目标 `graphId` 从 `GraphRegistry` 创建独立图并从其精确入口执行。等待线程使用
`Future.get(handoff.timeout())`；超时必须 `cancel(true)`，关闭子图并以
`AgentHandoffTimeoutException` 完成 Future，不留下运行虚拟线程。

`AgentHandoffEvent` 是独立 sealed Trace 协议，包含 `Started`、`NodeStarted`、`NodeProgress`、
`NodeCompleted`、`Completed`、`Failed` 六种事件。每个事件都携带 `taskId`、`parentRunId`、
`childRunId`、`fromAgent`、`toAgent` 和时间；节点事件另带精确节点名，失败事件只带完整异常栈。
事件正文不记录 `content` 或状态变量值。发布器异常不得吞掉执行异常；若失败发生在子图执行后，
发布异常作为 suppressed cause 保留。

子图返回 `GraphExecutionResult.Interrupted` 时抛 `AgentHandoffInterruptedException`，因为 5A 不允许
未持久化子运行进入 HITL 等待。调用方可以在父图中把完整异常写入状态并决定是否重试新的 handoff。

## 错误协议

- `AgentDescriptorException`：描述符、状态键集合或目录引用非法。
- `AgentNotFoundException`：精确 Agent ID 未注册。
- `AgentHandoffDeniedException`：目标不在白名单、自移交、访问环、深度或次数耗尽。
- `AgentHandoffStateException`：只读输入缺失/被修改、未知输出、请求输出缺失。
- `AgentStateMergeException`：父状态已有不同值，拒绝覆盖。
- `AgentHandoffTimeoutException`：单次子运行超时并已请求取消。
- `AgentHandoffInterruptedException`：子图请求未支持的嵌套 HITL。
- `AgentHandoffExecutionException`：保留目标 Agent、子运行标识和原始 cause。

所有异常都保存可程序读取的精确字段；不以异常 message 反向解析标识符。

## 测试与 EDD 门禁

### 领域测试

- 描述符集合冻结、重复 Agent、未知目标、自移交、状态键交叠全部拒绝。
- Handoff timeout、输出键、执行上下文深度/次数/访问链全部做边界测试。
- `FORK` 精确继承父消息，`FRESH` 精确丢弃父消息；两者只复制声明的变量。
- 子图修改只读键、写未知键、缺少请求输出和父状态冲突全部拒绝且不部分合并。

### 子图集成测试

- 使用两个真实 `StateGraph` 工厂执行 parent→worker，断言独立 `childRunId`、虚拟线程、结果合并、
  子图关闭和六类结构化事件。
- 阻塞节点超过 timeout 时 Future 以 `AgentHandoffTimeoutException` 失败，节点收到中断且执行线程退出。
- 子图中断时返回 `AgentHandoffInterruptedException`，不伪装成完成。

### EDD

`agent-eval/src/test/java/com/agent/eval/MultiAgentHandoffEddTest.java` 写入
`agent-eval/target/edd/multi-agent-handoff-edd.json`。字段精确为
`taskId/status/contextMode/fromAgent/toAgent/childRunDistinct/mergedKeys/eventCount/passed`；场景 ID
精确为 `handoff.fork`、`handoff.fresh`、`handoff.target-denied`、`handoff.cycle-denied`、
`handoff.depth-denied`、`handoff.state-ownership`、`handoff.merge-conflict`、`handoff.timeout`。
报告不得写任务正文、状态变量值或异常堆栈。

## 文档与后续接入

完成后在 `docs/ENGINEERING_PITFALLS.md` 记录自然语言目标注入、Fork 上下文污染、Fresh briefing
不足、状态键越权、循环移交、超时子线程残留和验证 Agent 确认偏误。5A 提供基础设施但不改变
现有生产图；第七篇接入时，执行型子任务使用 `FORK`，独立审查使用 `FRESH`。
