# 第二篇 2B：Memory、Runtime 与 Harness 设计

## 1. 目标与边界

本里程碑实现教程第二篇关于长期记忆生命周期、受控 Agent Runtime 和 Harness 治理的工程
实践。它只增强现有 `agent-rag`、`agent-core` 和 `agent-web` 的端口与装配，不引入
LangChain4j/LangGraph4j，不实现 MCP、Skills、Multi-Agent 或新的前端页面。

成功标准是：

- 动态记忆按重要度、访问频率和时间衰减排序；`ARCHITECTURE_RULE` 永不衰减。
- 图执行可被总时长、节点空闲时长、token 预算、最大步数和无进展次数任一门禁确定性停止。
- 停止原因以精确状态键写入 `AgentState`，由 `AgentRunService` 持久化为 Checkpoint，并
  通过已有 Trace 终态事件和日志可见。
- Harness Hook 按固定顺序执行节点/工具生命周期事件；观测 Hook 失败进入审计并继续，
  预算、权限和审批 Hook 失败拒绝执行且保留完整异常。
- 生产配置可以通过精确 `agent.production.*` 属性控制预算；旧构造器和旧图测试保持兼容。

## 2. 记忆生命周期模型

### 2.1 精确类型

`MemoryEntry` 在现有字段后追加：

- `double importance`：`0.0` 到 `1.0` 的有限值，表示提取时的重要度。
- `long accessCount`：非负访问次数。
- `Instant lastAccessedAt`：不早于 `createdAt`；访问时间允许晚于内容更新时间 `updatedAt`。

`MemoryDraft` 追加同名 `importance` 字段；现有三参数构造器保留并使用精确默认值
`0.5`，使既有调用方迁移可控。新模型 JSON 必须包含 `importance`，范围错误、缺失、null
或未知字段均失败。

`MemoryHit` 在现有 `entry/vectorScore/lexicalScore/finalScore` 后追加
`lifecycleScore/rankingScore`；`finalScore` 继续表示检索融合分数以保持旧调用方语义。
现有四参数构造器保留，并使用 `lifecycleScore=1.0`、
`rankingScore=0.8 * finalScore + 0.2`。

### 2.2 生命周期与排序

`MemoryType.ARCHITECTURE_RULE` 的衰减因子恒为 `1.0`。其余类型使用半衰期常量
`PREFERENCE_HALF_LIFE = Duration.ofDays(30)` 和 `BAD_CASE_HALF_LIFE = Duration.ofDays(14)`：

```text
decay = exp(-ln(2) * ageSeconds / halfLifeSeconds)
frequency = log1p(accessCount) / log1p(accessCount + 1)
lifecycleScore = importance * (0.7 + 0.3 * frequency) * decay
```

`MemoryManager.recall` 先按现有向量/词法两路融合得到 `retrievalScore`，再计算
`rankingScore = 0.8 * retrievalScore + 0.2 * lifecycleScore`，按 `rankingScore`、
`retrievalScore`、`updatedAt`、`memoryId` 依次稳定排序。
返回命中后通过 `MemoryStore.recordAccess` 在同一用户/仓库范围增加访问次数并刷新
`lastAccessedAt`。访问更新失败交给构造器注入的 `MemoryAuditSink`；Sink 失败通过
`MemoryStoreException.addSuppressed` 保留并写日志，已经计算的召回结果仍返回。
`MemoryAuditSink` 的唯一方法精确为
`void recordAccessFailure(MemoryQuery query, List<UUID> memoryIds, RuntimeException failure)`。

### 2.3 数据库迁移

新增 `agent-rag/src/main/resources/db/rag-migration/V3__add_memory_lifecycle.sql`，只对
`rag_memories` 增加 `importance double precision not null default 0.5`、
`access_count bigint not null default 0 check (access_count >= 0)` 和
`last_accessed_at timestamptz`。迁移先将已有行的 `last_accessed_at` 回填为 `updated_at`，
再设置 `not null` 和 `default current_timestamp`，并添加按 scope/updated_at 的索引。
迁移不可修改 V1/V2，也不使用 `if exists` 掩盖列缺失。

## 3. Runtime 执行预算

### 3.1 公开协议

`agent-core.engine.ExecutionBudget` 为不可变 record：

```java
ExecutionBudget(
    Duration maxDuration,
    Duration idleTimeout,
    long tokenBudget,
    int maxSteps,
    int noProgressLimit)
```

所有 Duration 必须为正，`tokenBudget`、`maxSteps`、`noProgressLimit` 必须大于 0。旧
`StateGraph(int maxSteps)` 和 `(int, InterruptPolicy)` 构造器创建其余预算为 `Duration.ofDays(3650)`、
`Duration.ofDays(3650)`、`Long.MAX_VALUE`、传入步数和 `Integer.MAX_VALUE` 的兼容预算。

`ExecutionStopReason` 只含 `MAX_DURATION`、`IDLE_TIMEOUT`、`TOKEN_BUDGET`、`MAX_STEPS`、
`NO_PROGRESS`。`ExecutionBudgetExceededException` 保存精确 reason、observed 和 limit。

### 3.2 运行语义

`StateGraph(ExecutionBudget, InterruptPolicy)` 使用单调 `System.nanoTime` 计算时长；包内测试
构造器可注入 `LongSupplier ticker`。每次节点前检查总时长、空闲时长、步数和 token 预算；
节点执行期间以 `Future.get` 的有界等待循环重新检查总时长与空闲时长，节点过程摘要更新空闲
时钟。节点可通过 `NodeExecutionContext.consumeTokens(long)` 记录本次模型/工具 token，负数
拒绝，累计超过上限时在当前虚拟线程立即抛出停止异常。节点返回状态的 `messages` 和
`variables` 与上一步相同即计为一次无进展，`trace` 不参与比较；达到 `noProgressLimit` 抛出
停止异常。预算检查按
`MAX_DURATION -> IDLE_TIMEOUT -> TOKEN_BUDGET -> MAX_STEPS -> NO_PROGRESS` 固定优先级。

### 3.3 持久化与 Trace

`AgentState` 使用精确键：

- `runtime.stopReason`
- `runtime.observed`
- `runtime.limit`
- `runtime.consumedTokens`

`AgentRunService.storeFailure` 遇到 `ExecutionBudgetExceededException` 时基于最新不可变状态
写入上述键，再追加 `FAILED` Checkpoint；普通异常保持原有语义。`TraceEvent.Failed.error`
包含停止原因和完整堆栈，既有 SSE/WebSocket 无需新增事件类型。生产配置新增：
`max-duration-ms`、`idle-timeout-ms`、`token-budget`、`no-progress-limit`，统一映射为
`ExecutionBudget`。

## 4. Harness Hook 链

### 4.1 事件协议

`agent-core.harness` 新增：

- `HarnessEventType`：`BEFORE_NODE`、`AFTER_NODE`、`BEFORE_TOOL`、`AFTER_TOOL`、`FAILURE`、
  `BUDGET_EXHAUSTED`。
- `HarnessEvent` record：`runId`、`nodeName`、`eventType`、`occurredAt`、`state`、`metadata`。
- `HarnessHook`：`void onEvent(HarnessEvent event)`，并声明 `critical()`。
- `HarnessHookChain`：按注册顺序执行，复制 Hook 列表后不可变。
- `HarnessHookException`：保存 Hook 名称、事件类型和原始 cause。

### 4.2 失败隔离

链在每个事件上顺序调用 Hook。非关键 Hook 抛错时将 `HarnessHookException` 加入审计收集器
并继续后续 Hook；关键 Hook 抛错立即停止并向 `StateGraph` 传播。节点异常先发布
`FAILURE` Hook，再保持原始 `GraphExecutionException` cause。预算停止先发布
`BUDGET_EXHAUSTED` Hook，再进入 `AgentRunService` 的失败 Checkpoint。

`HarnessAuditSink` 是核心层唯一审计端口；默认实现为 no-op，`agent-web` 适配已有日志与
Trace，不把 Hook 失败写入普通业务状态，避免观测故障改变 Agent 决策。

`NodeExecutionContext.callTool(String toolName, Map<String, String> metadata,
Callable<T> action)` 在同一节点上下文中发布 `BEFORE_TOOL` 与 `AFTER_TOOL`。`OpsNode` 的终端
执行和 `ReviewerNode` 的浏览器证据收集使用该入口；工具异常先发布 `FAILURE` 再原样抛出，
不把异常降级成成功的 `AFTER_TOOL`。

## 5. 测试与门禁

先写失败测试再实现：

1. `MemoryLifecycleTest` 验证半衰期、重要度、访问频率、规则不衰减和稳定排序。
2. `JdbcMemoryLifecycleIntegrationTest` 在 Docker PostgreSQL 中执行 V1→V2→V3，验证默认值、
   访问更新和 scope 隔离；不可用 Docker 时用 JUnit assumption 明确跳过。
3. `ExecutionBudgetTest` 验证五种停止原因、token 消耗、过程摘要刷新空闲时间、无进展
   和旧构造器兼容。
4. `HarnessHookChainTest` 验证顺序、非关键失败隔离、关键失败传播和完整 cause。
5. `AgentRunServiceBudgetTest` 验证停止键进入 FAILED Checkpoint 与 Trace 错误。
6. `ProductionAgentPropertiesTest` 验证精确环境属性和预算映射。

门禁命令固定为 Java 21 的 `mvn -pl agent-core,agent-rag,agent-web -am test`、适用的
真实 Docker 集成测试、`LlmEddTest` 环境开关以及 `mvn clean package -DskipTests`。
