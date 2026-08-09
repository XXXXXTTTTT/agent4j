# 5A Multi-Agent Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在自研 `StateGraph` 上增加受目录治理的 Agent Handoff、FORK/FRESH 上下文、有界独立子运行、结构化 Trace 与状态键所有权合并。

**Architecture:** `com.agent.core.multiagent` 保存不可变 Agent/Handoff 协议。`AgentCatalog` 只接受精确注册目标；`AgentStateProjector` 构造最小权限子状态并校验合并；`AgentHandoffExecutor` 使用虚拟线程创建独立子图和 `childRunId`，在 timeout 内执行并发布不含敏感正文的独立事件。现有 `StateGraph`、`AgentRunService` 和生产图保持兼容。

**Tech Stack:** Java 21 records/sealed types、现有 StateGraph/GraphRegistry、CompletableFuture、虚拟线程、JUnit 5、AssertJ、Jackson EDD。

---

## 文件结构

- Create: `agent-core/src/main/java/com/agent/core/multiagent/AgentDescriptor.java`、`AgentCatalog.java`。
- Create: `agent-core/src/main/java/com/agent/core/multiagent/HandoffContextMode.java`、`AgentHandoff.java`、`HandoffExecutionContext.java`、`AgentHandoffResult.java`。
- Create: `agent-core/src/main/java/com/agent/core/multiagent/AgentStateProjector.java`。
- Create: `agent-core/src/main/java/com/agent/core/multiagent/AgentHandoffEvent.java`、`AgentHandoffEventPublisher.java`。
- Create: `agent-core/src/main/java/com/agent/core/multiagent/AgentHandoffExecutor.java`。
- Create: `agent-core/src/main/java/com/agent/core/multiagent/*Exception.java` — 七类精确异常。
- Create: `agent-core/src/test/java/com/agent/core/multiagent/AgentCatalogTest.java`、`AgentHandoffTest.java`、`AgentStateProjectorTest.java`、`AgentHandoffExecutorTest.java`。
- Create: `agent-eval/src/test/java/com/agent/eval/MultiAgentHandoffEddTest.java`。
- Modify: `docs/ENGINEERING_PITFALLS.md`。

### Task 1: Agent 目录与 Handoff 领域协议

**Files:**
- Create: `AgentDescriptor.java`、`AgentCatalog.java`、`HandoffContextMode.java`、`AgentHandoff.java`、`HandoffExecutionContext.java` 及描述符/目录/拒绝异常。
- Test: `AgentCatalogTest.java`、`AgentHandoffTest.java`。

- [ ] **Step 1: Write the failing tests**

测试精确覆盖：目录输入冻结、重复 agentId、未知 handoffTargets、自移交、读写键交叠、精确 require；
handoff 空字段、10 分钟 timeout 上界、空 requestedOutputKeys；上下文 `root`、`descend`、深度、
剩余次数和访问环。

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-core '-Dtest=AgentCatalogTest,AgentHandoffTest' test`

Expected: FAIL at test compilation because `com.agent.core.multiagent` is absent.

- [ ] **Step 3: Write minimal implementation**

records 全部复制并冻结集合；目录两遍校验定义和精确目标引用；`HandoffExecutionContext.descend` 在
任何预算或循环条件不满足时抛 `AgentHandoffDeniedException`，成功时追加目标并减一。

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl agent-core '-Dtest=AgentCatalogTest,AgentHandoffTest' test`

- [ ] **Step 5: Commit**

```text
feat(multiagent): define governed handoff domain
```

### Task 2: FORK/FRESH 投影与所有权合并

**Files:**
- Create: `AgentStateProjector.java`、`AgentHandoffStateException.java`、`AgentStateMergeException.java`。
- Test: `AgentStateProjectorTest.java`。

- [ ] **Step 1: Write the failing tests**

断言 `FORK` 复制父 messages 并追加任务，`FRESH` 只含任务；两者只复制目标 readableStateKeys、
不继承父 trace。构造子最终状态验证只读键修改、未知键、请求输出缺失和父值冲突均失败；正常结果
只合并 requestedOutputKeys 并追加精确 handoff trace。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core '-Dtest=AgentStateProjectorTest' test`

Expected: FAIL because the projector is absent.

- [ ] **Step 3: Write minimal implementation**

`project` 从 descriptor 精确读取变量；缺失键立即失败。`merge` 先完整验证再创建新状态，防止部分
合并。父值不同一律拒绝，不提供覆盖开关；子 messages/trace 不合并。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-core '-Dtest=AgentStateProjectorTest' test`

- [ ] **Step 5: Commit**

```text
feat(multiagent): enforce state projection and ownership
```

### Task 3: 有界虚拟线程子运行与独立 Trace

**Files:**
- Create: `AgentHandoffEvent.java`、`AgentHandoffEventPublisher.java`、`AgentHandoffResult.java`、`AgentHandoffExecutor.java`、执行/超时/中断异常。
- Test: `AgentHandoffExecutorTest.java`。

- [ ] **Step 1: Write the failing tests**

使用两个真实 `StateGraph` 工厂：目标节点记录 `Thread.currentThread().isVirtual()` 并写拥有的输出键。
断言 childRunId 与 parentRunId 不同、目标图执行一次、合并正确、事件包含 Started/NodeStarted/
NodeProgress/NodeCompleted/Completed。阻塞节点测试 timeout、收到中断和 Failed；带 InterruptPolicy 的
目标图测试 nested HITL 拒绝。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core '-Dtest=AgentHandoffExecutorTest' test`

Expected: FAIL because the executor and trace protocol are absent.

- [ ] **Step 3: Write minimal implementation**

执行器先目录/白名单/上下文/输出键校验，再生成 childRunId 和子状态。工作 Future 在命名虚拟线程中
创建并 try-with-resources 关闭图；等待 Future 在另一虚拟线程使用 `get(timeout)`，超时 cancel。
所有路径发布结构化事件；发布器失败保留为主异常或 suppressed cause。正常完成后调用 projector
合并，Interrupted 转为精确异常。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-core '-Dtest=AgentHandoffExecutorTest' test`

- [ ] **Step 5: Commit**

```text
feat(multiagent): execute bounded traced subruns
```

### Task 4: EDD 与工程复盘

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/MultiAgentHandoffEddTest.java`。
- Modify: `docs/ENGINEERING_PITFALLS.md`。

- [ ] **Step 1: Write the failing EDD**

生成 `target/edd/multi-agent-handoff-edd.json`，字段精确为
`taskId/status/contextMode/fromAgent/toAgent/childRunDistinct/mergedKeys/eventCount/passed`，八个场景
ID 按设计文档固定。报告不含 content、变量值和堆栈。

- [ ] **Step 2: Run EDD to verify it fails**

Run: `mvn -pl agent-eval -am '-Dtest=MultiAgentHandoffEddTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

- [ ] **Step 3: Write minimal EDD and review entry**

EDD 使用确定性图和阻塞节点，不调用外部模型。复盘按问题现象、根因、代码级方案和测试证据记录
目标注入、上下文污染、状态越权、循环、超时残留和 Fresh 验证。

- [ ] **Step 4: Run EDD to verify it passes**

Run: `mvn -pl agent-eval -am '-Dtest=MultiAgentHandoffEddTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

- [ ] **Step 5: Commit**

```text
test(eval): add multi agent handoff edd
docs(knowledge): record multi agent handoff pitfalls
```

### Task 5: 全量验收

- [ ] **Step 1: Run focused and dependency-chain tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core '-Dtest=AgentCatalogTest,AgentHandoffTest,AgentStateProjectorTest,AgentHandoffExecutorTest' test
mvn -pl agent-core,agent-eval -am test
```

Expected: 0 failures/errors；既有外部 EDD 和基础设施门禁继续使用精确 assumption/环境开关。

- [ ] **Step 2: Run package and hygiene checks**

```powershell
mvn clean package '-DskipTests' '-Dfrontend.skip=true'
git -c safe.directory='D:/agent4j/.worktrees/guide-third-part-knowledge' diff --check
git -c safe.directory='D:/agent4j/.worktrees/guide-third-part-knowledge' status --short
```

Expected: 全模块打包成功；只包含 5A 原子提交，target、日志、`.env` 不进入 Git。
