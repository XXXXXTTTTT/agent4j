# 5B StateGraph Composition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为自研 `StateGraph` 增加不可变拓扑快照、严格结构校验、显式状态桥接子图，并以 EDD 证明条件循环和预算停止语义。

**Architecture:** `GraphTopologyAnalyzer` 只分析 StateGraph 已声明的节点和边，不执行 Condition；`StateGraph` 暴露快照与严格校验入口。`SubgraphNode` 通过 `SubgraphStateBridge` 把独立子状态映射进出新建的子图，并沿用父 `runId` 与现有 progress 通道；无效子图和嵌套 HITL 明确失败。

**Tech Stack:** Java 21 records/sealed types、现有 StateGraph/GraphFactory/GraphExecutionListener、虚拟线程、JUnit 5、AssertJ、Jackson EDD。

---

## 文件结构

- Create: `agent-core/src/main/java/com/agent/core/engine/GraphTopology.java` — 不可变拓扑快照和 `valid()`。
- Create: `agent-core/src/main/java/com/agent/core/engine/GraphTopologyException.java` — 保存完整无效快照。
- Create: `agent-core/src/main/java/com/agent/core/engine/GraphTopologyAnalyzer.java` — 可达性、反向终点可达性和强连通分量分析。
- Modify: `agent-core/src/main/java/com/agent/core/engine/StateGraph.java` — `inspectTopology()` 与 `validateTopology()`。
- Create: `agent-core/src/main/java/com/agent/core/engine/SubgraphStateBridge.java` — 显式父子状态转换端口。
- Create: `agent-core/src/main/java/com/agent/core/engine/SubgraphNode.java` — 同 Run 的严格校验子图节点。
- Create: `agent-core/src/main/java/com/agent/core/engine/SubgraphExecutionException.java`、`SubgraphInterruptedException.java` — 精确失败协议。
- Create: `agent-core/src/test/java/com/agent/core/engine/GraphTopologyTest.java`、`StateGraphTopologyTest.java`。
- Create: `agent-core/src/test/java/com/agent/core/engine/SubgraphNodeTest.java`。
- Create: `agent-eval/src/test/java/com/agent/eval/StateGraphCompositionEddTest.java`。
- Modify: `docs/ENGINEERING_PITFALLS.md`。

### Task 1: 不可变拓扑领域模型与分析器

**Files:**
- Create: `GraphTopology.java`、`GraphTopologyException.java`、`GraphTopologyAnalyzer.java`。
- Test: `GraphTopologyTest.java`。

- [ ] **Step 1: Write the failing test**

```java
@Test
void freezesNestedCollectionsAndDerivesValidity() {
    Map<String, Set<String>> outgoing = new LinkedHashMap<>();
    outgoing.put("start", new LinkedHashSet<>(Set.of(StateGraph.END)));
    GraphTopology topology = new GraphTopology(
            "start", Set.of("start"), outgoing,
            Set.of(), Set.of(), Set.of(), Set.of());
    outgoing.get("start").add("other");
    assertThat(topology.outgoingTargets().get("start"))
            .containsExactly(StateGraph.END);
    assertThat(topology.valid()).isTrue();
    assertThatThrownBy(() -> topology.outgoingTargets().clear())
            .isInstanceOf(UnsupportedOperationException.class);
}
```

另写 package-private analyzer 测试数据，精确验证线性图、带出口的两节点环、自环、不可达节点、
无出边和闭环无 END；`GraphTopologyException.topology()` 必须返回同一不可变快照。

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-core '-Dtest=GraphTopologyTest' test`

Expected: FAIL at test compilation because topology types are absent.

- [ ] **Step 3: Write minimal implementation**

`GraphTopology` 构造时逐层复制 `outgoingTargets` 的 Map 与每个 Set，并校验 entry/node/target 精确文本。
`GraphTopologyAnalyzer.analyze(entryPoint, nodeNames, outgoingTargets)`：

1. 从入口 DFS 得到 reachable，差集得到 `unreachableNodes`。
2. 空 outgoing 的注册节点进入 `deadEndNodes`。
3. 构造包含 `END` 的反向邻接表，从 `END` 反向 DFS，注册节点差集得到 `nodesWithoutEndPath`。
4. 对注册节点运行 Tarjan SCC；大小大于 1 的分量全部加入 `cyclicNodes`，大小为 1 且存在自环时加入。

所有结果先使用 `LinkedHashSet` 保持输入顺序，再由 record 冻结。`valid()` 只检查三个无效集合；
`GraphTopologyException` message 列出集合，字段保存 topology。

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl agent-core '-Dtest=GraphTopologyTest' test`

- [ ] **Step 5: Commit**

```text
feat(graph): add immutable topology analysis
```

### Task 2: StateGraph 快照与严格校验入口

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/engine/StateGraph.java`。
- Test: `agent-core/src/test/java/com/agent/core/engine/StateGraphTopologyTest.java`。

- [ ] **Step 1: Write the failing test**

```java
@Test
void validatesConditionalCycleWithEndRoute() {
    try (StateGraph graph = new StateGraph(5)) {
        graph.addNode("agent", state -> state)
                .addNode("tool", state -> state)
                .addConditionalEdges("agent", state -> "end",
                        Map.of("tool", "tool", "end", StateGraph.END))
                .addEdge("tool", "agent")
                .setEntryPoint("agent");
        GraphTopology topology = graph.validateTopology();
        assertThat(topology.valid()).isTrue();
        assertThat(topology.cyclicNodes()).containsExactlyInAnyOrder("agent", "tool");
    }
}
```

再覆盖旧快照不受后续 builder 修改影响、无入口拒绝、不可达/死端/无 END 路径严格失败，以及
`inspectTopology()` 对同一无效图只返回证据不抛异常。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core '-Dtest=StateGraphTopologyTest' test`

Expected: FAIL because `inspectTopology` and `validateTopology` are absent.

- [ ] **Step 3: Write minimal implementation**

在 `StateGraph` 中新增两个公开方法。内部 `snapshotOutgoingTargets()` 为每个已注册节点创建集合：普通边
放一个目标，条件边放 routes.values()，未配置边保留空集合。`inspectTopology()` 要求图未关闭且入口
已设置，把 `nodes.keySet()` 和快照交给 analyzer。`validateTopology()` 调用 inspect，invalid 时抛
`GraphTopologyException`。现有 `execute` 不自动调用严格校验，保持纯预算循环等现有兼容行为。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-core '-Dtest=StateGraphTopologyTest,StateGraphTest,StateGraphBudgetTest' test`

- [ ] **Step 5: Commit**

```text
feat(graph): expose strict topology validation
```

### Task 3: 显式状态桥接子图节点

**Files:**
- Create: `SubgraphStateBridge.java`、`SubgraphNode.java`、`SubgraphExecutionException.java`、`SubgraphInterruptedException.java`。
- Test: `SubgraphNodeTest.java`。

- [ ] **Step 1: Write the failing test**

```java
@Test
void bridgesIndependentStateAndKeepsParentRunId() {
    AtomicReference<UUID> childRunId = new AtomicReference<>();
    GraphFactory factory = () -> graphWithTwoNodes(childRunId);
    SubgraphStateBridge bridge = new SubgraphStateBridge() {
        public AgentState project(AgentState parent) {
            return AgentState.empty().withVariable("query", parent.variables().get("task"));
        }
        public AgentState merge(AgentState parent, AgentState child) {
            return parent.withVariable("report", child.variables().get("summary"));
        }
    };
    try (StateGraph parent = parentGraph(new SubgraphNode("research", factory, bridge))) {
        UUID runId = UUID.randomUUID();
        GraphExecutionResult result = parent.execute(
                new GraphExecutionRequest(runId,
                        AgentState.empty().withVariable("task", "java"),
                        parent.entryPoint(), false), listener);
        assertThat(childRunId).hasValue(runId);
        assertThat(((GraphExecutionResult.Completed) result).state().variables())
                .containsEntry("report", "done").doesNotContainKey("query");
    }
}
```

再覆盖子节点虚拟线程、精确 progress 顺序、每次 factory 创建新图、无效拓扑执行次数为零、bridge/factory
null、子节点 IOException cause 和子图 InterruptRequest。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core '-Dtest=SubgraphNodeTest' test`

Expected: FAIL because subgraph types are absent.

- [ ] **Step 3: Write minimal implementation**

`SubgraphStateBridge` 定义 project/merge。`SubgraphNode` 校验 `subgraphId/factory/bridge`，覆盖双参数
`Node.execute`；投影后创建图并 try-with-resources，先 `validateTopology()`，再以父 context.runId 和
子图入口执行。包装 `GraphExecutionListener`，只通过 `NodeExecutionContext.progress` 发布规格中的
五种固定摘要。Completed 调用 merge；Interrupted 抛保存 request 的 `SubgraphInterruptedException`；
Topology/Interrupted 原样抛，其他异常包装为保留 cause 的 `SubgraphExecutionException`。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-core '-Dtest=SubgraphNodeTest,StateGraphTest' test`

- [ ] **Step 5: Commit**

```text
feat(graph): add explicitly bridged subgraph node
```

### Task 4: 循环停止 EDD 与工程复盘

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/StateGraphCompositionEddTest.java`。
- Modify: `docs/ENGINEERING_PITFALLS.md`。

- [ ] **Step 1: Write the EDD test**

生成 `target/edd/state-graph-composition-edd.json`，字段精确为
`taskId/status/valid/unreachableNodes/deadEndNodes/nodesWithoutEndPath/cyclicNodes/stopReason/passed`。
八个任务 ID 按设计固定；无停止原因的场景输出 null，`graph.loop-budget` 必须从捕获的
`ExecutionBudgetExceededException.reason()` 写入 `MAX_STEPS`，不得从异常 message 解析。

- [ ] **Step 2: Run EDD to verify current coverage fails**

Run: `mvn -pl agent-eval -am '-Dtest=StateGraphCompositionEddTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: 首次 FAIL because the EDD class and 5B APIs are not yet present in the pre-implementation baseline;
after Tasks 1–3, the new test must compile and expose any remaining contract mismatch.

- [ ] **Step 3: Complete EDD and review entry**

线性/条件环使用 validate；三类无效图从 `GraphTopologyException.topology()` 取字段；子图桥接断言
结果但报告不写值；中断断言精确异常；纯自环使用 `ExecutionBudget(maxSteps=2)` 捕获强类型 reason。
复盘记录构造成功不等于可终止、条件环、全状态复制、隐式合并、嵌套 HITL 与无 reducer 并行风险。

- [ ] **Step 4: Run EDD to verify it passes**

Run: `mvn -pl agent-eval -am '-Dtest=StateGraphCompositionEddTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

- [ ] **Step 5: Commit**

```text
test(eval): add state graph composition edd
docs(knowledge): record state graph composition pitfalls
```

### Task 5: 全量验收

- [ ] **Step 1: Run focused and dependency-chain tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core '-Dtest=GraphTopologyTest,StateGraphTopologyTest,SubgraphNodeTest,StateGraphTest,StateGraphBudgetTest' test
mvn -pl agent-core,agent-eval -am test
```

Expected: 0 failures/errors；既有 Docker、PTY、Playwright 和外部 EDD 继续使用当前精确门禁。

- [ ] **Step 2: Run clean package and hygiene checks**

```powershell
mvn clean package '-DskipTests' '-Dfrontend.skip=true'
mvn -pl agent-eval -am '-Dtest=StateGraphCompositionEddTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
git -c safe.directory='D:/agent4j/.worktrees/guide-third-part-knowledge' diff --check
git -c safe.directory='D:/agent4j/.worktrees/guide-third-part-knowledge' status --short
```

Expected: 全模块打包成功，EDD 报告重新生成，工作树干净；target、日志和 `.env` 不进入 Git。
