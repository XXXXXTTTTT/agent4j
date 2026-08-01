# Phase 4 Harness Engineering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为现有图引擎增加 PostgreSQL 追加式 Checkpoint、强类型 HITL 挂起与恢复、异步 Run 生命周期、REST API 和 WebSocket Trace。

**Architecture:** `agent-core` 只拥有不可变领域模型、可恢复图执行和虚拟线程运行服务；`agent-web` 通过 Spring JDBC、Flyway、WebFlux 与进程内事件总线实现基础设施适配器。PostgreSQL 是唯一权威状态，Trace 失败不回滚 Checkpoint，每次执行使用并关闭独立 `StateGraph`。

**Tech Stack:** Java 21、Spring Boot 3.3.13、Spring JDBC、Flyway 10.10.0、PostgreSQL JDBC 42.7.7、Testcontainers 1.19.8、WebFlux、Reactor、JUnit 5、AssertJ、Mockito。

---

## 文件映射

### agent-core

- `engine/RunStatus.java`：Run 精确状态。
- `engine/ApprovalDecision.java`、`ApprovalCommand.java`：强类型审批输入。
- `engine/InterruptRequest.java`、`InterruptPolicy.java`：节点执行前中断协议。
- `engine/RunCheckpoint.java`、`CheckpointAppend.java`、`Checkpointer.java`：追加式快照端口。
- `engine/CheckpointConflictException.java`、`RunNotFoundException.java`：持久化并发与不存在错误。
- `engine/GraphExecutionRequest.java`、`GraphExecutionListener.java`、`GraphExecutionResult.java`：可恢复图执行协议。
- `engine/StateGraph.java`：中断、指定入口恢复、单次 bypass 和 listener 顺序。
- `engine/GraphFactory.java`、`GraphRegistry.java`、`GraphNotFoundException.java`：按精确 graphId 创建独立图。
- `trace/TraceEventType.java`、`TraceEvent.java`、`TraceEventPublisher.java`：强类型 Trace 端口。
- `engine/AgentRunService.java`：虚拟线程调度、Checkpoint、审批、恢复和 Trace。

### agent-web

- `pom.xml`：JDBC、Flyway、PostgreSQL 与 Testcontainers 依赖。
- `resources/db/migration/V1__create_agent_run_tables.sql`：Run 与 Checkpoint 表。
- `persistence/JdbcCheckpointer.java`：事务、JSONB 与乐观锁。
- `controller/RunController.java`、`RunView.java`、`StartRunRequest.java`、`ApprovalRequest.java`：Run REST 协议。
- `controller/RunExceptionHandler.java`：精确 `ProblemDetail` 映射。
- `trace/InMemoryTraceEventBus.java`：按 runId 隔离的 Reactor sink。
- `trace/RunTraceWebSocketHandler.java`、`TraceWebSocketConfiguration.java`：SNAPSHOT 后 EVENT。
- `config/HarnessConfiguration.java`、`RunRecoveryListener.java`：构造器注入与启动恢复。

## Task 1: Checkpoint 与中断领域模型

**Files:**
- Create: `agent-core/src/test/java/com/agent/core/engine/CheckpointModelTest.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/RunStatus.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/ApprovalDecision.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/ApprovalCommand.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/InterruptRequest.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/InterruptPolicy.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/RunCheckpoint.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/CheckpointAppend.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/Checkpointer.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/CheckpointConflictException.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/RunNotFoundException.java`

- [ ] **Step 1: 写领域约束失败测试**

`CheckpointModelTest` 使用固定 UUID、`AgentState.empty()` 与 `Instant.EPOCH`，覆盖：

```java
assertThat(new ApprovalCommand(ApprovalDecision.APPROVE, 0, "已核对").reason())
        .isEqualTo("已核对");
assertThatThrownBy(() -> new ApprovalCommand(ApprovalDecision.APPROVE, -1, "已核对"))
        .isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> new RunCheckpoint(
        runId, 1, "graph", RunStatus.WAITING_APPROVAL, AgentState.empty(),
        "ops", null, null, null, null, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
```

逐一覆盖设计中的 `RUNNING`、`WAITING_APPROVAL`、`COMPLETED`、`REJECTED`、`FAILED`
字段组合；验证 `InterruptRequest.details()` 防御性复制；验证两个异常保存精确 runId，
冲突异常同时保存 `expectedVersion`。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-core -am "-Dtest=CheckpointModelTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 测试编译失败，原因是上述公开类型尚不存在。

- [ ] **Step 3: 实现精确枚举、record 与端口**

枚举值固定为：

```java
public enum RunStatus {
    RUNNING, WAITING_APPROVAL, COMPLETED, REJECTED, FAILED
}

public enum ApprovalDecision {
    APPROVE, REJECT
}
```

所有 record 紧凑构造器执行设计文档的非空、非空白、版本和状态组合校验；
`InterruptRequest` 使用 `Map.copyOf`。`InterruptPolicy.never()` 精确返回
`(runId, nodeName, state) -> Optional.empty()`。`Checkpointer` 方法签名与设计文档完全一致。

- [ ] **Step 4: 运行模型测试并确认绿灯**

Run: `mvn -pl agent-core -am "-Dtest=CheckpointModelTest,AgentStateTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 所有领域约束、不可变集合与现有状态测试通过。

- [ ] **Step 5: 提交领域模型**

```text
feat(core): 定义运行 Checkpoint 与中断协议
```

## Task 2: StateGraph 可恢复执行

**Files:**
- Create: `agent-core/src/test/java/com/agent/core/engine/StateGraphInterruptTest.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/GraphExecutionRequest.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/GraphExecutionListener.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/GraphExecutionResult.java`
- Modify: `agent-core/src/main/java/com/agent/core/engine/StateGraph.java`

- [ ] **Step 1: 写中断与恢复失败测试**

建立 `first -> guarded -> END` 图，策略只在 `guarded` 返回固定 `InterruptRequest`。
覆盖：首次中断不调用 guarded 节点与 started listener；从 guarded 且 bypass 为 true 恢复；
bypass 后若再次到达 guarded 仍检查策略；listener 精确顺序为 started、completed；未知
startNode、策略返回不同 nodeName、null listener 均拒绝；`execute(AgentState)` 行为不变。

核心断言：

```java
GraphExecutionResult result = graph.execute(
        new GraphExecutionRequest(runId, AgentState.empty(), "guarded", true), listener);
assertThat(result).isInstanceOf(GraphExecutionResult.Completed.class);
assertThat(events).containsExactly("started:guarded", "completed:guarded:__END__");
```

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-core -am "-Dtest=StateGraphInterruptTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译失败，原因是可恢复图协议尚不存在。

- [ ] **Step 3: 实现图执行协议**

`GraphExecutionRequest` 校验 runId、state、非空 startNode；listener 增加 `noop()`；结果为：

```java
public sealed interface GraphExecutionResult
        permits GraphExecutionResult.Completed, GraphExecutionResult.Interrupted {
    record Completed(AgentState state) implements GraphExecutionResult { }
    record Interrupted(AgentState state, String nodeName, InterruptRequest request)
            implements GraphExecutionResult { }
}
```

record 紧凑构造器执行非空与精确节点一致性校验。

- [ ] **Step 4: 扩展 StateGraph**

保留 `StateGraph(int maxSteps)` 并委托到 `StateGraph(int maxSteps, InterruptPolicy policy)`。
新增 `entryPoint()` 和新 execute 重载。循环顺序固定为：校验节点、按一次性 bypass 判断策略、
started、虚拟线程节点执行、解析下一节点、completed、移动游标。原 execute 委托新协议并只接受
`Completed`；默认策略确保旧行为不会产生 `Interrupted`。

- [ ] **Step 5: 运行新旧图测试并确认绿灯**

Run: `mvn -pl agent-core -am "-Dtest=StateGraphInterruptTest,StateGraphTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 中断、恢复、listener、虚拟线程、最大步数和现有图测试全部通过。

- [ ] **Step 6: 提交图执行扩展**

```text
feat(core): 支持状态图挂起与指定节点恢复
```

## Task 3: GraphRegistry 与 Trace 领域协议

**Files:**
- Create: `agent-core/src/test/java/com/agent/core/engine/GraphRegistryTest.java`
- Create: `agent-core/src/test/java/com/agent/core/trace/TraceEventTest.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/GraphFactory.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/GraphRegistry.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/GraphNotFoundException.java`
- Create: `agent-core/src/main/java/com/agent/core/trace/TraceEventType.java`
- Create: `agent-core/src/main/java/com/agent/core/trace/TraceEvent.java`
- Create: `agent-core/src/main/java/com/agent/core/trace/TraceEventPublisher.java`

- [ ] **Step 1: 写注册表与 Trace 失败测试**

验证 `GraphRegistry.create("coder-ops-reviewer")` 两次返回不同实例；未知值和
`"CODER-OPS-REVIEWER"` 均抛 `GraphNotFoundException`；空 Map、空键、null factory 和
factory 返回 null 均拒绝。Trace 测试使用 Jackson 往返七种事件，断言 `type` 精确为
枚举名，公共字段不丢失，文本和版本约束生效。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-core -am "-Dtest=GraphRegistryTest,TraceEventTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译失败，原因是注册表和 Trace 类型尚不存在。

- [ ] **Step 3: 实现注册表**

构造器逐项校验后保存 `Map.copyOf`；`create` 只调用精确键对应 factory，未知键抛出保存
graphId 的 `GraphNotFoundException`，factory 返回 null 立即失败。

- [ ] **Step 4: 实现七种 Trace 事件**

`TraceEvent` 使用 `@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")` 与七个
`@JsonSubTypes.Type`，公开统一访问器 `eventId()`、`runId()`、`checkpointVersion()`、
`occurredAt()`、`type()`。七个 record 精确对应 `NODE_STARTED`、`NODE_COMPLETED`、
`INTERRUPTED`、`APPROVED`、`REJECTED`、`FAILED`、`COMPLETED`；publisher 提供
`noop()`。

- [ ] **Step 5: 运行测试并确认绿灯**

Run: `mvn -pl agent-core -am "-Dtest=GraphRegistryTest,TraceEventTest,ChatMessageTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 精确 graphId、独立图、强类型 JSON 与现有 Jackson 测试通过。

- [ ] **Step 6: 提交注册表与 Trace 协议**

```text
feat(core): 定义图注册与运行 Trace 协议
```

## Task 4: AgentRunService 生命周期

**Files:**
- Create: `agent-core/src/test/java/com/agent/core/engine/AgentRunServiceTest.java`
- Create: `agent-core/src/main/java/com/agent/core/engine/AgentRunService.java`

- [ ] **Step 1: 写运行服务失败测试**

测试内实现线程安全内存 Checkpointer，使用 `CountDownLatch` 等待异步状态，不使用 sleep。
覆盖：start 立即返回版本 0；节点在线程名 `agent-run-` 的虚拟线程调度；逐节点追加；末节点
只追加一次 `COMPLETED`；中断追加 `WAITING_APPROVAL`；批准追加 `RUNNING` 后从精确节点
单次 bypass；拒绝追加 `REJECTED` 且不创建图；恢复普通与批准 Run；节点异常保存完整栈；
并发审批只有一个成功；Trace 七种类型与版本匹配；publisher 抛错不改变 Checkpoint；
每个图在完成、中断和失败后均关闭。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-core -am "-Dtest=AgentRunServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译失败，原因是 `AgentRunService` 尚不存在。

- [ ] **Step 3: 实现虚拟线程调度与 start/get**

服务实现 `AutoCloseable`，执行器精确为：

```java
Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("agent-run-", 0).factory())
```

`start` 创建 UUID 和版本 0 后提交 `executeCheckpoint(checkpoint, false)`；`get` 只读取
Checkpointer。提交被拒绝时把完整栈追加为 `FAILED`，不得返回内存伪状态。

- [ ] **Step 4: 实现节点 Checkpoint 与 Trace**

每次执行创建独立图并在 `finally` 关闭。listener 每完成节点，以原子引用中的最新版本
append；下一节点为 END 时追加 `COMPLETED`，否则追加无审批标记的 `RUNNING`。中断结果
追加 `WAITING_APPROVAL`。图返回 Completed 后不追加第二个终态。异常转完整栈后尝试追加
`FAILED`；发布异常只记录到 `System.Logger`，不回滚已提交状态。

- [ ] **Step 5: 实现 decide 与恢复**

`decide` 先加载最新版本，要求 WAITING_APPROVAL 且版本精确相同。REJECT 追加终态并发布；
APPROVE 追加携带批准理由的 RUNNING 并异步恢复。`recoverRunningRuns` 遍历精确 RUNNING，
只有最新 checkpoint 的 approvalDecision 为 APPROVE 时启用一次 bypass。状态或版本不符
统一抛出保存精确 runId 与 expectedVersion 的 `CheckpointConflictException`。

- [ ] **Step 6: 运行服务与图测试并确认绿灯**

Run: `mvn -pl agent-core -am "-Dtest=AgentRunServiceTest,StateGraphInterruptTest,GraphRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 生命周期、并发冲突、恢复、完整错误栈、Trace 与资源关闭全部通过。

- [ ] **Step 7: 提交运行服务**

```text
feat(core): 实现可恢复 Agent 运行服务
```

## Task 5: PostgreSQL Schema 与 JdbcCheckpointer

**Files:**
- Modify: `agent-web/pom.xml`
- Create: `agent-web/src/main/resources/db/migration/V1__create_agent_run_tables.sql`
- Create: `agent-web/src/test/java/com/agent/web/persistence/JdbcCheckpointerTest.java`
- Create: `agent-web/src/main/java/com/agent/web/persistence/JdbcCheckpointer.java`

- [ ] **Step 1: 添加数据库与真实容器测试依赖**

增加 `spring-boot-starter-jdbc`、`flyway-core`、`flyway-database-postgresql`、runtime
`org.postgresql:postgresql`、test `org.testcontainers:postgresql` 与
`org.testcontainers:junit-jupiter`，版本全部由 Spring Boot 3.3.13 管理。

- [ ] **Step 2: 写 PostgreSQL 失败测试**

`JdbcCheckpointerTest` 使用静态 `PostgreSQLContainer<?>` 镜像精确为
`postgres:16-alpine`。Docker 不可用时用 JUnit assumption 明确跳过；当前环境必须执行。
测试内以容器 JDBC 参数构造 `DriverManagerDataSource`、`Flyway.configure().dataSource(...).load().migrate()`、
`JdbcClient`、`TransactionTemplate` 与项目 ObjectMapper。

覆盖 Flyway 两张表；多模态 AgentState JSONB 往返；create 版本 0；append 历史与最新；
按状态查询；不存在；两个并发 append 只有一个成功；更新成功但插入非法快照时事务回滚。

- [ ] **Step 3: 运行测试并确认红灯**

Run: `mvn -pl agent-web -am "-Dtest=JdbcCheckpointerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译失败，原因是迁移与 `JdbcCheckpointer` 尚不存在；当前环境容器不得 skipped。

- [ ] **Step 4: 创建精确 PostgreSQL Schema**

迁移创建设计中的 `agent_runs`、`agent_checkpoints`、主外键、CHECK 约束及
`(status, updated_at)`、`(run_id, version desc)` 索引。状态字段只允许五个精确枚举值。

- [ ] **Step 5: 实现 JdbcCheckpointer**

构造器注入 `JdbcClient`、`TransactionTemplate`、`ObjectMapper` 和 `Clock`。create 在单事务
插入 run 与版本 0；append 先按 runId 与 expectedVersion 更新一行，再插入版本 +1；0 行时
查询 runId 区分 not found/conflict。JSON 用 ObjectMapper 读写 AgentState 与
InterruptRequest，SQL 参数使用 PostgreSQL `PGobject` 且 type 精确为 `jsonb`。

查询使用一个集中 `RowMapper<RunCheckpoint>`，枚举用 `valueOf` 精确解析，不转换大小写。

- [ ] **Step 6: 运行数据库测试并确认绿灯**

Run: `mvn -pl agent-web -am "-Dtest=JdbcCheckpointerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 真实 PostgreSQL 迁移、JSONB、事务、历史和乐观锁全部通过，skipped 为 0。

- [ ] **Step 7: 提交持久化适配器**

```text
feat(web): 实现 PostgreSQL 运行 Checkpoint
```

## Task 6: Run REST API

**Files:**
- Create: `agent-web/src/test/java/com/agent/web/controller/RunControllerTest.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/StartRunRequest.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/ApprovalRequest.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/RunView.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/RunController.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/RunExceptionHandler.java`

- [ ] **Step 1: 写 WebTestClient 失败测试**

使用 `@WebFluxTest(RunController.class)` 和 mock `AgentRunService`。精确覆盖：创建 202、查询
200、批准和拒绝 202；JSON 字段等于设计；未知 graph/run 为 404；版本与状态冲突 409；
非法 UUID、枚举大小写变化、负版本、空 reason、缺字段、多余字段和错误 state 结构为 400；
未处理异常为 500，错误体只断言标准 ProblemDetail 字段。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-web -am "-Dtest=RunControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译失败，原因是 REST 公开类型尚不存在。

- [ ] **Step 3: 实现请求与视图 record**

`StartRunRequest` 精确字段为 `graphId`、`initialState`；`ApprovalRequest` 精确字段为
`decision`、`expectedVersion`、`reason`；二者用 Jakarta Validation。使用 Jackson
`@JsonIgnoreProperties(ignoreUnknown = false)` 拒绝多余字段。`RunView.from` 映射
RunCheckpoint 的全部 12 个精确字段。

- [ ] **Step 4: 实现 Controller 与异常映射**

三个 endpoint 精确为 `/api/runs`、`/api/runs/{runId}`、
`/api/runs/{runId}/approval`。创建与审批返回 `ResponseEntity.accepted()`，查询返回 200。
`RunExceptionHandler` 映射 400/404/409/500，ProblemDetail instance 使用当前请求 URI，
不添加额外属性。

- [ ] **Step 5: 运行 REST 测试并确认绿灯**

Run: `mvn -pl agent-web -am "-Dtest=RunControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: HTTP 状态、精确 JSON、严格输入与错误映射全部通过。

- [ ] **Step 6: 提交 REST API**

```text
feat(web): 提供 Agent Run REST API
```

## Task 7: WebSocket Trace

**Files:**
- Create: `agent-web/src/test/java/com/agent/web/trace/InMemoryTraceEventBusTest.java`
- Create: `agent-web/src/test/java/com/agent/web/trace/RunTraceWebSocketTest.java`
- Create: `agent-web/src/main/java/com/agent/web/trace/InMemoryTraceEventBus.java`
- Create: `agent-web/src/main/java/com/agent/web/trace/RunTraceWebSocketHandler.java`
- Create: `agent-web/src/main/java/com/agent/web/trace/TraceWebSocketConfiguration.java`

- [ ] **Step 1: 写事件总线失败测试**

验证两个 runId 完全隔离；同一 run 保持发布顺序；第 257 个未消费事件使该订阅完成并记录
溢出；终态完成 sink；新订阅不会重放旧事件；publisher close 后拒绝发布且不会泄漏 sink。

- [ ] **Step 2: 写随机端口 WebSocket 失败测试**

使用真实 `ReactorNettyWebSocketClient` 连接精确路径。mock Checkpointer 返回固定 Run；
断言首帧 `kind=SNAPSHOT` 且包含 `run`，后续发布后收到 `kind=EVENT` 与强类型 event；
验证 runId 隔离、终态关闭、客户端断开清理，以及不存在 run 关闭码 4404。

- [ ] **Step 3: 运行测试并确认红灯**

Run: `mvn -pl agent-web -am "-Dtest=InMemoryTraceEventBusTest,RunTraceWebSocketTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译失败，原因是 Trace 总线和 Handler 尚不存在。

- [ ] **Step 4: 实现总线与帧协议**

`InMemoryTraceEventBus` 同时实现 `TraceEventPublisher` 与 `AutoCloseable`，公开精确
`Flux<TraceEvent> subscribe(UUID runId)`。每个 run 使用容量 256 的 unicast sink；发布
终态后删除并完成 sink。帧使用两个 record：`TraceSnapshotFrame(String kind, RunView run)`
与 `TraceEventFrame(String kind, TraceEvent event)`，kind 分别固定为 SNAPSHOT/EVENT。

- [ ] **Step 5: 实现 WebSocket 路由**

配置使用 `SimpleUrlHandlerMapping` 精确注册 `/ws/runs/{runId}/trace` 的 pattern handler，
`WebSocketHandlerAdapter` 完成升级。Handler 从 session URI 路径提取精确 UUID，先
`loadLatest`，再 `Flux.concat(snapshot, events)` 序列化为文本消息；不存在 run 发送
`CloseStatus(4404, "run not found")`。

- [ ] **Step 6: 运行 WebSocket 测试并确认绿灯**

Run: `mvn -pl agent-web -am "-Dtest=InMemoryTraceEventBusTest,RunTraceWebSocketTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: SNAPSHOT/EVENT 顺序、隔离、背压、关闭状态和订阅清理全部通过。

- [ ] **Step 7: 提交 Trace 推送**

```text
feat(web): 实现 WebSocket 运行 Trace 推送
```

## Task 8: Spring 装配与启动恢复

**Files:**
- Modify: `agent-web/src/test/java/com/agent/web/AgentWebApplicationTest.java`
- Create: `agent-web/src/test/java/com/agent/web/config/RunRecoveryListenerTest.java`
- Create: `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`
- Create: `agent-web/src/main/java/com/agent/web/config/RunRecoveryListener.java`

- [ ] **Step 1: 写装配与恢复失败测试**

`RunRecoveryListenerTest` mock AgentRunService，调用 ApplicationReadyEvent 后精确一次
`recoverRunningRuns()`；异常不吞掉。上下文测试提供测试专用 DataSource 与精确
`GraphFactory` Bean，断言 Checkpointer、GraphRegistry、Trace bus、AgentRunService、
Controller、WebSocket Handler 均存在。不得注册虚构生产 graph。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl agent-web -am "-Dtest=AgentWebApplicationTest,RunRecoveryListenerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译或上下文失败，原因是配置与恢复 listener 尚不存在。

- [ ] **Step 3: 实现构造器注入配置**

配置从容器提供的 `JdbcClient`、`PlatformTransactionManager`、ObjectMapper 与 Clock 创建
JdbcCheckpointer；从精确 `Map<String, GraphFactory>` 创建 GraphRegistry；创建单例
InMemoryTraceEventBus 与 AgentRunService。所有资源 Bean 声明 destroyMethod=`close`。

- [ ] **Step 4: 实现启动恢复**

`RunRecoveryListener` 构造器注入 AgentRunService，`@EventListener(ApplicationReadyEvent.class)`
方法只调用一次 `recoverRunningRuns()`，不捕获异常。

- [ ] **Step 5: 运行装配测试并确认绿灯**

Run: `mvn -pl agent-web -am "-Dtest=AgentWebApplicationTest,RunRecoveryListenerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 测试配置下所有 Bean 正常装配，恢复只触发一次。

- [ ] **Step 6: 提交 Spring 装配**

```text
feat(web): 装配 Harness 生命周期与启动恢复
```

## Task 9: 真实 Phase 4 生命周期集成

**Files:**
- Create: `agent-web/src/test/java/com/agent/web/RunLifecycleIntegrationTest.java`
- Create: `agent-web/src/test/java/com/agent/web/RunRecoveryIntegrationTest.java`

- [ ] **Step 1: 写真实 REST、WebSocket、PostgreSQL 闭环测试**

共享 `postgres:16-alpine` 测试基础设施并注册精确 `approval-flow` 图：`prepare -> ops -> end`，
InterruptPolicy 只在 ops 节点产生固定中断。通过真实 REST 创建，WebSocket 先收 SNAPSHOT，
轮询 REST 直到 WAITING_APPROVAL，以其精确 version APPROVE，最终轮询到 COMPLETED。
断言历史版本单调、ops 只执行一次、Trace 顺序包含 INTERRUPTED、APPROVED、COMPLETED，
且所有 JSON 字段与数据库快照一致。

- [ ] **Step 2: 运行闭环测试并确认红灯**

Run: `mvn -pl agent-web -am "-Dtest=RunLifecycleIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 首次运行暴露尚未联通的配置或生命周期缺口；当前环境 PostgreSQL 不得 skipped。

- [ ] **Step 3: 只修复闭环暴露的精确缺口**

根据失败栈修改已创建的最小生产文件，不增加设计外协议。修复后再次运行同一命令，直到
REST、WebSocket、RunService 与 PostgreSQL 闭环通过。

- [ ] **Step 4: 写恢复集成失败测试**

直接写入普通 RUNNING 与带 APPROVE 的 RUNNING Checkpoint，重建 AgentRunService 并调用
recoverRunningRuns。断言普通运行从 nextNode 恢复；批准运行只对起始节点 bypass；起始
节点完成后的下一个同策略节点再次 WAITING_APPROVAL；每个终态只追加一次。

- [ ] **Step 5: 运行恢复测试并确认绿灯**

Run: `mvn -pl agent-web -am "-Dtest=RunLifecycleIntegrationTest,RunRecoveryIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 两个真实 PostgreSQL 集成测试通过，skipped 为 0，无容器、线程或连接遗留。

- [ ] **Step 6: 提交完整闭环**

```text
test(web): 验证 Harness 挂起恢复闭环
```

## Task 10: Phase 4 完整验收与本地合并

**Files:**
- Verify: `.gitignore`
- Verify: all Phase 4 production, migration and test files

- [ ] **Step 1: 记录容器与 Java 进程基线**

Run:

```powershell
$phase4ContainerBaseline = docker ps -aq
$phase4JavaBaseline = Get-Process java -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty Id
```

Expected: 命令成功，不终止任何既有资源。

- [ ] **Step 2: 使用 JDK 21 完整验证**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
mvn clean verify
```

Expected: Java 精确主版本为 21，五个 reactor 模块全部 SUCCESS，失败与错误为 0；当前
环境 `JdbcCheckpointerTest`、`RunLifecycleIntegrationTest`、
`RunRecoveryIntegrationTest` skipped 均为 0。

- [ ] **Step 3: 汇总 Surefire 报告**

Run:

```powershell
$phase4Reports = Get-ChildItem -Recurse -Filter 'TEST-*.xml' |
    Where-Object { $_.FullName -match '\\target\\surefire-reports\\' }
$phase4Totals = [ordered]@{ Tests = 0; Failures = 0; Errors = 0; Skipped = 0 }
foreach ($phase4Report in $phase4Reports) {
    [xml]$phase4Xml = Get-Content -Raw $phase4Report.FullName
    $phase4Totals.Tests += [int]$phase4Xml.testsuite.tests
    $phase4Totals.Failures += [int]$phase4Xml.testsuite.failures
    $phase4Totals.Errors += [int]$phase4Xml.testsuite.errors
    $phase4Totals.Skipped += [int]$phase4Xml.testsuite.skipped
}
$phase4Totals
```

Expected: Failures、Errors 都为 0；三个 PostgreSQL suite 的各自 XML skipped 为 0。

- [ ] **Step 4: 检查依赖、容器、进程和排除规则**

Run: `mvn -pl agent-core,agent-web -am dependency:tree`

Expected: 包含 JDBC、Flyway、PostgreSQL、Testcontainers，不含 `langchain4j` 或
`langgraph4j`。

Run:

```powershell
$phase4NewContainers = docker ps -aq | Where-Object { $_ -notin $phase4ContainerBaseline }
$phase4NewJava = Get-Process java -ErrorAction SilentlyContinue |
    Where-Object { $_.Id -notin $phase4JavaBaseline }
$phase4NewContainers
$phase4NewJava | Select-Object Id,ProcessName,Path
git check-ignore -v agent-core/target agent-web/target tmp
```

Expected: 无本阶段遗留容器或 Java 进程；三个路径由根 `.gitignore` 排除。

- [ ] **Step 5: 检查 Git 原子性**

Run:

```powershell
git status --short --branch
git log --oneline --decorate -15
git diff 93e3600..HEAD --check
```

Expected: 工作树干净；Phase 4 提交均为 scope 必填的 Conventional Commits；无构建产物、
IDE、日志、临时文件或密钥进入版本库。

- [ ] **Step 6: 快进合并并在 master 复验**

从 `D:\agent4j` 精确确认 master 仍指向 Phase 4 分支起点后执行 `git merge --ff-only
feat/phase-4-harness`。在 master 上重新运行 `mvn clean verify` 和报告汇总；只有合并结果
验证通过、Git 干净且无资源遗留后，才关闭 Goal。
