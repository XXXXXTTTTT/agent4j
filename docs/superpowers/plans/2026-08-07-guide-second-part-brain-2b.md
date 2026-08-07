# 第二篇 2B：Memory、Runtime 与 Harness 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不破坏现有 Agent 图和持久化协议的前提下，引入长期记忆生命周期排序、可持久化执行预算和可审计 Harness Hook 链。

**Architecture:** `agent-rag` 继续通过 `MemoryStore` 管理 PostgreSQL 记忆生命周期；`agent-core` 以不可变 `ExecutionBudget`、`ExecutionStopReason` 和 `HarnessHookChain` 治理图执行；`agent-web` 只负责配置绑定、Checkpoint/Trace 可见性和生产装配。所有新行为用兼容构造器接入，旧测试先保持绿色。

**Tech Stack:** Java 21 records、Spring JDBC/PostgreSQL、JUnit 5、Testcontainers、现有 StateGraph/Trace/Checkpoint。

---

### Task 1: 记忆生命周期领域协议

**Files:**
- Modify: `agent-rag/src/main/java/com/agent/rag/memory/MemoryDraft.java`
- Modify: `agent-rag/src/main/java/com/agent/rag/memory/MemoryEntry.java`
- Modify: `agent-rag/src/main/java/com/agent/rag/memory/MemoryHit.java`
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryLifecycle.java`
- Create: `agent-rag/src/test/java/com/agent/rag/memory/MemoryLifecycleTest.java`

- [ ] **Step 1: 写失败测试**：构造带 `importance=0.8`、`accessCount=3`、固定 `lastAccessedAt` 的条目，断言架构规则生命周期分数恒等于重要度；偏好和 Bad Case 按精确 30/14 天半衰期计算；非有限值、负访问次数和非法时间关系失败；旧三参数 `MemoryDraft` 保留默认重要度 `0.5`。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=MemoryLifecycleTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期因生命周期类型和新构造器不存在而编译失败。
- [ ] **Step 3: 写最小实现**：新增 `MemoryLifecycle`，固定半衰期和 `score(MemoryEntry, Instant)`；扩展两个 record，保留旧构造器并复制数组；`MemoryHit` 添加 `lifecycleScore` 并保留旧四参数构造器计算默认值。
- [ ] **Step 4: 运行绿灯**：重复命令，预期生命周期测试全部通过。
- [ ] **Step 5: 提交**：`git add agent-rag/src/main agent-rag/src/test; git commit -m "feat(memory): add lifecycle scoring"`。

### Task 2: PostgreSQL V3 与访问更新

**Files:**
- Create: `agent-rag/src/main/resources/db/rag-migration/V3__add_memory_lifecycle.sql`
- Modify: `agent-rag/src/main/java/com/agent/rag/memory/MemoryStore.java`
- Modify: `agent-rag/src/main/java/com/agent/rag/memory/JdbcMemoryStore.java`
- Create: `agent-rag/src/test/java/com/agent/rag/memory/JdbcMemoryLifecycleIntegrationTest.java`

- [ ] **Step 1: 写失败集成测试**：Docker 不可用时用 assumption；执行 V1、V2、V3，断言三列、默认值、scope/updated 索引存在；upsert 保留旧 ID/createdAt；`recordAccess` 精确递增并刷新时间，跨用户/仓库不能互串。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=JdbcMemoryLifecycleIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期 V3、列和方法不存在。
- [ ] **Step 3: 写最小实现**：只新增 V3；`MemoryStore.recordAccess` 提供默认 no-op 兼容测试替身；JDBC 用单条参数化 `update ... where memory_id in (...) and repository_id=? and user_id=?`，任何 DataAccessException 包装为 `MemoryStoreException`。
- [ ] **Step 4: 运行绿灯**：重复集成命令并运行 `mvn -pl agent-rag -am test`，Docker 可用时不得 skip。
- [ ] **Step 5: 提交**：`git commit -m "feat(memory): persist lifecycle metadata"`。

### Task 3: MemoryManager 生命周期召回

**Files:**
- Modify: `agent-rag/src/main/java/com/agent/rag/memory/MemoryManager.java`
- Modify: `agent-rag/src/main/java/com/agent/rag/memory/JdbcMemoryStore.java`
- Create: `agent-rag/src/main/java/com/agent/rag/memory/MemoryAuditSink.java`
- Modify: `agent-rag/src/test/java/com/agent/rag/memory/MemoryManagerTest.java`
- Create: `agent-rag/src/test/java/com/agent/rag/memory/MemoryLifecycleManagerTest.java`

- [ ] **Step 1: 写失败测试**：固定 Clock 和三条重叠命中，断言 `rankingScore = 0.8 * retrievalScore + 0.2 * lifecycleScore`、规则不衰减、访问更新调用一次；访问更新异常只进入 `MemoryAuditSink`，不改变已经计算的结果。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-rag -am "-Dtest=MemoryLifecycleManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期 MemoryManager 未计算生命周期分数。
- [ ] **Step 3: 写最小实现**：在合并命中后计算生命周期分数、调用 `recordAccess`；提供构造器注入的 `MemoryAuditSink`，访问更新异常完整交给 Sink，Sink 异常作为 suppressed 并写日志；保持向量/词法归一化和 scope 校验。
- [ ] **Step 4: 运行绿灯与回归**：重复测试并运行 `mvn -pl agent-rag -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(memory): rank recalls by lifecycle"`。

### Task 4: ExecutionBudget 与停止异常

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/engine/ExecutionBudget.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/ExecutionStopReason.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/ExecutionBudgetExceededException.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/NodeExecutionContext.java`
- Create: `agent-core/src/test/java/com/agent/core/engine/ExecutionBudgetTest.java`

- [ ] **Step 1: 写失败测试**：验证正数/Duration 校验、五种停止原因的异常字段、`consumeTokens` 拒绝负数并累计、Progress 更新时间、无进展签名比较；旧上下文构造器的 token 计数仍为 0。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-core -am "-Dtest=ExecutionBudgetTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期类型不存在。
- [ ] **Step 3: 写最小实现**：实现不可变预算 record 和停止异常；`NodeExecutionContext` 增加线程绑定的 token Consumer、`consumeTokens(long)` 和 `progress` 时钟回调，旧构造器使用 no-op。
- [ ] **Step 4: 运行绿灯**：重复测试，确认没有改变现有 MDC/Progress 行为。
- [ ] **Step 5: 提交**：`git commit -m "feat(runtime): define execution budgets"`。

### Task 5: StateGraph 预算调度

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/engine/StateGraph.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/AgentRunService.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/NodeExecutionContext.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/OpsNode.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/ReviewerNode.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/MaxStepsExceededException.java`
- Create: `agent-core/src/test/java/com/agent/core/engine/StateGraphBudgetTest.java`
- Create: `agent-core/src/test/java/com/agent/core/engine/AgentRunServiceBudgetTest.java`

- [ ] **Step 1: 写失败测试**：使用短 Duration、token Consumer、重复返回相同状态的节点，分别断言总时长、空闲、token、步数和无进展停止；服务层断言 `runtime.stopReason/observed/limit/consumedTokens` 进入 FAILED Checkpoint 与 `TraceEvent.Failed.error`。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-core -am "-Dtest=StateGraphBudgetTest,AgentRunServiceBudgetTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期 StateGraph 没有预算构造器和停止逻辑。
- [ ] **Step 3: 写最小实现**：新增 `StateGraph(ExecutionBudget, InterruptPolicy)`；旧构造器生成兼容预算；循环在固定优先级检查预算，节点 Progress 刷新 idle，节点状态无变化累计 no-progress；预算异常保留最后状态和 observed 数据，服务层用不可变 `withVariable` 写停止键。
- [ ] **Step 4: 运行绿灯与回归**：重复测试并运行 `mvn -pl agent-core -am test`。
- [ ] **Step 5: 提交**：`git commit -m "feat(runtime): enforce graph execution budgets"`。

### Task 6: Harness Hook 链

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/harness/HarnessEventType.java`
- Create: `agent-core/src/main/java/com/agent/core/harness/HarnessEvent.java`
- Create: `agent-core/src/main/java/com/agent/core/harness/HarnessHook.java`
- Create: `agent-core/src/main/java/com/agent/core/harness/HarnessHookChain.java`
- Create: `agent-core/src/main/java/com/agent/core/harness/HarnessHookException.java`
- Create: `agent-core/src/main/java/com/agent/core/harness/HarnessAuditSink.java`
- Create: `agent-core/src/test/java/com/agent/core/harness/HarnessHookChainTest.java`

- [ ] **Step 1: 写失败测试**：断言事件 record 的精确字段、Hook 注册顺序、非关键 Hook 异常进入 AuditSink 后继续、关键 Hook 异常停止并保留 cause、Hook 列表不可变。
- [ ] **Step 2: 运行红灯**：`mvn -pl agent-core -am "-Dtest=HarnessHookChainTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，预期包和类型不存在。
- [ ] **Step 3: 写最小实现**：按固定 `HarnessEventType` 实现 record、接口、链和异常；AuditSink 只接收 `HarnessHookException`，默认 no-op；禁止 Hook 修改状态。
- [ ] **Step 4: 运行绿灯**：重复测试，检查异常 cause 与事件元数据精确保留。
- [ ] **Step 5: 提交**：`git commit -m "feat(harness): add ordered hook chain"`。

### Task 7: StateGraph Harness 接线与生产可见性

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/engine/StateGraph.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/AgentRunService.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionAgentProperties.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Modify: `agent-web/src/main/resources/application.properties`
- Modify: `agent-web/src/main/resources/.env.example`
- Create: `agent-core/src/test/java/com/agent/core/engine/StateGraphHarnessTest.java`
- Modify: `agent-web/src/test/java/com/agent/web/config/ProductionAgentPropertiesTest.java`

- [ ] **Step 1: 写失败测试**：构造 Hook 链并运行真实图，断言节点前后事件、终端/浏览器工具前后事件、失败事件和预算事件顺序；属性测试断言五个精确环境字段绑定，旧配置仍能构造。
- [ ] **Step 2: 运行红灯**：分别运行核心和 Web 指定测试，预期 StateGraph/Properties 没有 Hook 和预算接线。
- [ ] **Step 3: 写最小实现**：StateGraph 新增带 HookChain 的构造器；`NodeExecutionContext.callTool` 发布工具边界，Ops/Reviewer 接入该入口；生产属性新增 `maxDuration/idleTimeout/tokenBudget/noProgressLimit` 并构造 ExecutionBudget；停止原因通过已有 Failed Trace 和 Checkpoint 状态键输出。
- [ ] **Step 4: 运行绿灯与全量回归**：`mvn -pl agent-core,agent-web -am test`，确认 Docker/PTY/浏览器回归通过或 assumption 明确跳过。
- [ ] **Step 5: 提交**：`git commit -m "feat(agent): expose runtime budgets and harness hooks"`。

### Task 8: 复盘、EDD 与最终门禁

**Files:**
- Modify: `docs/ENGINEERING_PITFALLS.md`
- Optional Modify: `agent-eval/src/test/java/com/agent/eval/LlmEddTest.java`（仅增加 2B 任务）

- [ ] **Step 1: 写 EDD 任务**：增加预算耗尽、重复无进展、记忆长期偏好和 Bad Case 召回任务，报告必须包含 stopReason、observed、limit、trace 和错误分类。
- [ ] **Step 2: 更新复盘**：记录记忆衰减、访问更新竞态、预算停止优先级、Progress 不能等同 token、Hook 失败隔离等已验证问题。
- [ ] **Step 3: 全量门禁**：Java 21 运行 `mvn -pl agent-core,agent-rag,agent-web,agent-eval -am test`、EDD 开关测试和 `mvn clean package "-DskipTests" "-Dfrontend.skip=true"`；运行 `git diff --check`、依赖禁止词扫描和 Docker 容器清理检查。
- [ ] **Step 4: 提交文档**：`git commit -m "docs(engineering): record runtime and harness pitfalls"`。
- [ ] **Step 5: 仅在所有门禁通过后**更新 Goal 状态为 complete。
