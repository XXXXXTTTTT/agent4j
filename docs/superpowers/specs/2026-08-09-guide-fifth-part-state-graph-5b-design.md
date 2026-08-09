# 第五篇 5B：自研 StateGraph 子图、拓扑校验与循环停止设计

## 章节边界

路线图把本里程碑标为第五篇第 18 章 `LangGraph`；教程目录
`tmp/ai-agent-guide-reference/js/main.js` 将“LangGraph 与状态机”精确映射到
`chapters/ch11-langgraph.html`。教程页面标题为第 17 章，核心实践包括显式 State/Node/Edge、
条件循环、Checkpoint、HITL、子图和事件流。

Agent4J 已有不可变 `AgentState`、普通边、条件边、虚拟线程执行、PostgreSQL Checkpoint、HITL、
`GraphExecutionListener`、五类 `ExecutionStopReason` 和 5A 有界 Handoff。本规格只补齐现有自研
引擎的子图模块化和执行前拓扑证据，不引入 LangGraph、LangChain4j 或任何 Python/TypeScript
运行时。

## 路线选择

### 采用：兼容增强

保留 `StateGraph` 的现有构造器和执行语义，新增不可变拓扑快照、显式严格校验和 `SubgraphNode`。
主图与子图使用独立 `AgentState`，通过调用方提供的强类型桥接器转换。现有顶层图不会因新增校验
自动改变行为；需要生产门禁的调用方显式使用 `validateTopology()`，`SubgraphNode` 在执行前强制
校验子图。

### 不采用：引入 LangGraph

这会破坏 Java 21 自研图引擎和无框架锁定原则，并重复现有 Checkpoint、HITL、Trace 与预算能力。

### 不采用：本里程碑增加并行扇出

并行节点必须先定义同一 `AgentState` 键的追加、覆盖、冲突和失败原子性。当前变量值为
`Map<String, String>`，没有字段级 reducer；直接并行会制造完成顺序决定结果的非确定性。5B 只做
教程和路线图明确要求的子图、拓扑与循环停止，后续若引入并行，必须先独立设计强类型合并协议。

## 拓扑快照

### 公开协议

```java
public record GraphTopology(
        String entryPoint,
        Set<String> nodeNames,
        Map<String, Set<String>> outgoingTargets,
        Set<String> unreachableNodes,
        Set<String> deadEndNodes,
        Set<String> nodesWithoutEndPath,
        Set<String> cyclicNodes) {
    public boolean valid();
}

public final class GraphTopologyException extends IllegalStateException {
    public GraphTopology topology();
}
```

`StateGraph.inspectTopology()` 返回调用时的不可变快照；`StateGraph.validateTopology()` 返回同一结构，
但在 `valid()==false` 时抛 `GraphTopologyException`。两个方法都要求已设置入口，不关闭图，也不执行
节点或 Condition。

### 精确计算规则

- `nodeNames` 是已注册节点精确名称的不可变集合；`StateGraph.END` 不属于节点集合。
- `outgoingTargets` 为每个节点保存普通边目标，或条件边所有路由目标的去重集合；无出边节点保存
  空集合。条件键不进入该映射，不能通过执行 Condition 推断拓扑。
- `unreachableNodes`：从 `entryPoint` 沿所有声明目标遍历后未访问的注册节点。
- `deadEndNodes`：没有普通边或条件边的注册节点。
- `nodesWithoutEndPath`：从该节点沿任意声明边都无法到达 `StateGraph.END` 的节点。通过反向图从
  `END` 遍历计算，不调用运行时 Condition。
- `cyclicNodes`：位于有向环中的节点；自环也属于环。实现使用确定性强连通分量算法，集合只保存
  精确节点名，不保存推测的运行次数。
- `valid()` 仅在 `unreachableNodes`、`deadEndNodes` 和 `nodesWithoutEndPath` 都为空时返回 true。
  `cyclicNodes` 非空本身不是错误：ReAct 的条件循环只要结构上存在到 `END` 的路径就是合法图。

`GraphTopologyException` 保存完整快照，异常 message 只列出三个无效集合。调用方不得从 message
反向解析节点名。

## 显式状态桥接子图

### 桥接器

```java
public interface SubgraphStateBridge {
    AgentState project(AgentState parentState);
    AgentState merge(AgentState parentState, AgentState childState);
}
```

桥接器由装配层实现，负责主/子状态字段的精确转换。`project` 和 `merge` 返回值均不得为 null。
引擎不复制全部变量、不按前缀猜测键、不自动合并 messages 或 trace；这避免子图私有状态污染主图。

### 子图节点

```java
public final class SubgraphNode implements Node {
    public SubgraphNode(
            String subgraphId,
            GraphFactory graphFactory,
            SubgraphStateBridge stateBridge);
}
```

`SubgraphNode.execute(NodeExecutionContext, AgentState)` 的顺序固定为：

1. 调用 `stateBridge.project(parentState)` 创建独立子状态。
2. 调用 `graphFactory.create()` 创建本次执行专用图，null 立即失败。
3. `try-with-resources` 关闭子图，并在任何节点执行前调用 `validateTopology()`。
4. 使用父节点 `NodeExecutionContext.runId()` 创建 `GraphExecutionRequest`，从子图精确入口执行；子图
   属于同一父 Run，不创建第二个 Checkpoint Run。
5. 子图开始、节点开始/过程/完成及子图完成通过父节点 `NodeExecutionContext.progress` 发布固定摘要：
   `subgraph:<subgraphId>:started`、`subgraph:<subgraphId>:node:<nodeName>:started`、
   `subgraph:<subgraphId>:node:<nodeName>:<summary>`、
   `subgraph:<subgraphId>:node:<nodeName>:completed:<nextNode>`、
   `subgraph:<subgraphId>:completed`。
6. 正常完成后调用 `stateBridge.merge(parentState, childState)`，返回新的父状态。

子图节点在独立 `StateGraph` 虚拟线程中运行；同一 `runId` 让 MDC 和 Trace 可关联，节点精确名称由
摘要中的 `subgraphId/nodeName` 区分。父 `StateGraph` 的总时长与空闲预算仍是最终边界；子图内部
预算由其 `GraphFactory` 固定，不能由任务正文覆盖。

### 中断和失败

- 子图返回 `GraphExecutionResult.Interrupted` 时抛 `SubgraphInterruptedException`，保存
  `subgraphId`、精确 `nodeName` 和原始 `InterruptRequest`。5B 不把未独立持久化的子图挂起伪装成
  顶层等待审批。
- 子图创建、拓扑、节点、投影或合并失败统一保留原始 cause；节点/桥接失败由
  `SubgraphExecutionException` 保存 `subgraphId`，但 `GraphTopologyException` 与
  `SubgraphInterruptedException` 保持自身精确类型。
- 父线程被中断时，现有 `StateGraph` 会取消正在运行的 `SubgraphNode`；嵌套子图的
  `StateGraph.execute` 收到中断后继续取消当前子节点并关闭执行器。

## 循环停止证据

5B 不新增第二套停止枚举。现有 `ExecutionStopReason` 已精确包含：

- `MAX_DURATION`
- `IDLE_TIMEOUT`
- `TOKEN_BUDGET`
- `MAX_STEPS`
- `NO_PROGRESS`

`ExecutionBudgetExceededException` 保存 `reason/observed/limit/consumedTokens`；
`AgentRunService` 已把原因写入 `runtime.stopReason` 和失败 Checkpoint。5B 增加拓扑中的
`cyclicNodes` 和 EDD，证明“结构上允许循环、运行时必须由条件路由或预算确定性停止”，不把异常
转成普通完成结果。

## 测试门禁

### 拓扑测试

- 线性图：所有集合为空、`valid=true`。
- 条件 ReAct 环且存在 `END` 路由：`cyclicNodes` 精确包含循环节点，仍然有效。
- 自环：节点进入 `cyclicNodes`。
- 不可达节点、无出边节点、无法到达 `END` 的闭环分别进入精确集合，严格校验抛异常并保存快照。
- 快照集合和嵌套 Map/Set 不可变，创建快照后继续修改 builder 不影响旧快照。

### 子图测试

- 主图 parent→subgraph→end，桥接器只传入指定字段并只合并指定结果；子 messages、私有变量和 trace
  不进入父状态。
- 子图的两个节点均运行于虚拟线程，获得与父节点相同 `runId`，固定 progress 摘要完整且有序。
- 每次父图执行创建新的子图并关闭；无效拓扑在任何子节点调用前失败。
- bridge 返回 null、factory 返回 null、子节点异常、子图中断均保留精确异常与 cause。

### EDD

`agent-eval/src/test/java/com/agent/eval/StateGraphCompositionEddTest.java` 写入
`agent-eval/target/edd/state-graph-composition-edd.json`，字段精确为
`taskId/status/valid/unreachableNodes/deadEndNodes/nodesWithoutEndPath/cyclicNodes/stopReason/passed`。
场景 ID 精确为 `graph.linear`、`graph.react-cycle`、`graph.unreachable`、`graph.dead-end`、
`graph.no-end-path`、`graph.subgraph-bridge`、`graph.subgraph-interrupt`、`graph.loop-budget`。
报告不写状态变量值、消息正文、异常堆栈或 Condition 实现。

## 文档与后续边界

完成后更新 `docs/ENGINEERING_PITFALLS.md`，记录“图能构造不等于能终止”、条件环的结构有效性、
子图全状态复制、隐式合并、嵌套 HITL 和并行 reducer 缺失问题。第六篇再增加第三方框架依赖守卫和
只读 Agent Profile；5B 不改 Web API，不接入生产 Coder→Ops→Reviewer 图。
