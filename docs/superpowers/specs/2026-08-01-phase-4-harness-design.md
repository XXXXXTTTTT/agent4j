# Phase 4 Harness Engineering Design

## 目标

Phase 4 为现有图引擎增加可持久化、可挂起、可恢复和可观测的运行生命周期：

- PostgreSQL 保存追加式版本 Checkpoint，并以 Run 元数据行提供乐观锁。
- `StateGraph` 在节点执行前通过强类型策略产生 HITL 中断。
- 人工批准后从精确节点恢复，人工拒绝后终止为 `REJECTED`。
- `AgentRunService` 使用 Java 21 虚拟线程异步启动和恢复运行。
- `agent-web` 提供 Run REST API 与 WebSocket 快照加实时 Trace。
- Testcontainers 使用真实 PostgreSQL 验证 JSONB、事务和并发冲突。

本设计延续 Phase 1-3 的不可变状态、构造器注入、完整异常栈、TDD 和原子提交规则，
不引入第三方 Agent 或工作流框架。

## 已确认决策

- PostgreSQL 是 Checkpoint 唯一权威数据源。
- 数据访问使用 Spring JDBC 与 Flyway，不使用 JPA 或 R2DBC。
- Core 注入 `InterruptPolicy`，不根据命令文本推断危险性。
- 审批拒绝终止 Run，并保留原因、待执行节点和最后状态。
- Trace 使用强类型进程内发布端口；本阶段不引入 Redis。
- REST 采用 `/api/runs` 资源模型，WebSocket 使用
  `/ws/runs/{runId}/trace`。
- `POST /api/runs` 使用 `graphId` 从注入式 `GraphRegistry` 创建图。
- Checkpoint 使用 `(runId, version)` 追加式快照。
- 审批请求包含 `decision`、`expectedVersion` 和非空 `reason`。
- WebSocket 先发送当前快照，再发送实时事件。
- PostgreSQL 集成测试使用 Testcontainers；当前 Docker 环境必须执行。

## 模块边界

### agent-core

`agent-core` 拥有运行生命周期的领域类型和执行语义：

- `Checkpointer` 及其不可变输入输出模型。
- `RunStatus`、审批决定、HITL 中断类型。
- 可从指定节点恢复的 `StateGraph` 执行协议。
- `GraphRegistry` 与独立图工厂。
- `AgentRunService` 虚拟线程调度器。
- 强类型 Trace 事件与发布端口。

Core 不依赖 JDBC、PostgreSQL、Flyway、WebFlux、WebSocket 或 Spring Bean 容器。

### agent-web

`agent-web` 实现基础设施适配器：

- `JdbcCheckpointer` 与 PostgreSQL 事务。
- Flyway 数据库迁移。
- Run REST Controller 与 `ProblemDetail` 异常映射。
- 进程内 Trace 总线与 WebSocket Handler。
- Spring 配置、启动恢复和 Graph Bean 注册。

`agent-web` 依赖 `agent-core`，Core 不反向依赖 Web。

## Core 公开类型

以下类型位于 `com.agent.core.engine`。

### Run 状态与审批

```java
public enum RunStatus {
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    REJECTED,
    FAILED
}

public enum ApprovalDecision {
    APPROVE,
    REJECT
}

public record ApprovalCommand(
        ApprovalDecision decision,
        long expectedVersion,
        String reason) {
}
```

`expectedVersion` 必须大于等于 0，`reason` 必须非空。JSON 枚举值只接受精确大写
字符串，不执行大小写转换。

### 中断协议

```java
public record InterruptRequest(
        UUID interruptId,
        String nodeName,
        String reason,
        Map<String, String> details) {
}

@FunctionalInterface
public interface InterruptPolicy {
    Optional<InterruptRequest> evaluate(
            UUID runId,
            String nodeName,
            AgentState state);
}
```

所有字段均不能为空，`nodeName` 与 `reason` 必须非空，`details` 防御性复制。
`InterruptPolicy.never()` 返回永不中断的策略。策略返回的 `nodeName` 必须与正在
执行的精确节点名相同，否则图执行失败，不修正名称。

### Checkpoint 模型

```java
public record RunCheckpoint(
        UUID runId,
        long version,
        String graphId,
        RunStatus status,
        AgentState state,
        String nextNode,
        InterruptRequest interruptRequest,
        ApprovalDecision approvalDecision,
        String approvalReason,
        String error,
        Instant createdAt) {
}

public record CheckpointAppend(
        UUID runId,
        long expectedVersion,
        RunStatus status,
        AgentState state,
        String nextNode,
        InterruptRequest interruptRequest,
        ApprovalDecision approvalDecision,
        String approvalReason,
        String error) {
}

public interface Checkpointer {
    RunCheckpoint create(
            UUID runId,
            String graphId,
            AgentState initialState,
            String entryNode);

    RunCheckpoint append(CheckpointAppend append);

    Optional<RunCheckpoint> loadLatest(UUID runId);

    List<RunCheckpoint> loadHistory(UUID runId);

    List<RunCheckpoint> loadLatestByStatus(RunStatus status);
}
```

状态约束如下：

- `RUNNING` 必须有非空 `nextNode`，不得携带 `interruptRequest` 或 `error`。
- `WAITING_APPROVAL` 必须有 `nextNode` 与 `interruptRequest`，二者节点名相同。
- `COMPLETED`、`REJECTED`、`FAILED` 的 `nextNode` 必须为 null。
- `REJECTED` 必须携带 `REJECT` 与非空审批原因。
- 审批后的 `RUNNING` 必须携带 `APPROVE` 与非空审批原因。
- `FAILED` 必须携带完整非空错误栈。
- 版本 0 只能由 `create` 产生；`append` 生成 `expectedVersion + 1`。

`CheckpointConflictException` 表示乐观锁冲突，`RunNotFoundException` 表示 Run 不存在。
两个异常均保留精确 `runId`；冲突异常还保留 `expectedVersion`。

### 可恢复图执行

```java
public record GraphExecutionRequest(
        UUID runId,
        AgentState state,
        String startNode,
        boolean bypassInterruptAtStart) {
}

public interface GraphExecutionListener {
    void onNodeStarted(String nodeName, AgentState state);

    void onNodeCompleted(
            String nodeName,
            String nextNode,
            AgentState state);
}

public sealed interface GraphExecutionResult
        permits GraphExecutionResult.Completed,
                GraphExecutionResult.Interrupted {

    record Completed(AgentState state) implements GraphExecutionResult {
    }

    record Interrupted(
            AgentState state,
            String nodeName,
            InterruptRequest request) implements GraphExecutionResult {
    }
}
```

`StateGraph` 增加接收 `InterruptPolicy` 的构造器，并保留现有
`StateGraph(int maxSteps)` 与 `execute(AgentState)` 行为。新方法为：

```java
public String entryPoint()

public GraphExecutionResult execute(
        GraphExecutionRequest request,
        GraphExecutionListener listener)
```

执行顺序固定为：

1. 校验 `startNode` 已注册。
2. 若 `bypassInterruptAtStart` 为 false，在节点执行前调用策略。
3. 策略返回中断时立即返回 `Interrupted`，不调用节点和 started listener。
4. 调用 `onNodeStarted`，随后在现有虚拟线程执行节点。
5. 解析精确下一节点，再调用 `onNodeCompleted`。
6. 后续节点恢复正常中断检查；bypass 只作用于起始节点一次。
7. 到达 `StateGraph.END` 后返回 `Completed`。

Listener 异常不被忽略，图执行立即失败并保留 cause。节点完成 Checkpoint 发生在
外部副作用之后，因此进程在节点完成与 Checkpoint 提交之间崩溃时采用至少一次执行
语义；具有外部副作用的节点必须由业务实现幂等。

### GraphRegistry

```java
@FunctionalInterface
public interface GraphFactory {
    StateGraph create();
}

public final class GraphRegistry {
    public GraphRegistry(Map<String, GraphFactory> factories);

    public StateGraph create(String graphId);
}
```

构造器拒绝空 Map、空 `graphId`、null factory，并防御性复制。未知 `graphId` 抛出
`GraphNotFoundException`，不执行格式或大小写匹配。每次调用必须返回新的图实例。

## AgentRunService

`AgentRunService` 位于 `com.agent.core.engine`，构造器注入 `Checkpointer`、
`GraphRegistry` 与 `TraceEventPublisher`，内部使用
`Executors.newVirtualThreadPerTaskExecutor()`。

公开方法为：

```java
public RunCheckpoint start(String graphId, AgentState initialState)

public RunCheckpoint get(UUID runId)

public RunCheckpoint decide(UUID runId, ApprovalCommand command)

public void recoverRunningRuns()
```

`start` 生成 UUID，创建版本 0 `RUNNING` Checkpoint 后异步执行并立即返回。

节点完成时 listener 以最新版本为 `expectedVersion` 追加 `RUNNING` Checkpoint；若
下一节点是 `END`，RunService 改为追加唯一一次 `COMPLETED`，图返回的 `Completed`
结果不得再次追加终态。非终点节点完成后追加的 `RUNNING` 不携带审批决定与审批原因，
因此批准标记只在被批准节点成功完成前有效。中断时追加 `WAITING_APPROVAL`。任何执行
异常都以 `printStackTrace` 的完整文本追加 `FAILED`。

`decide` 只接受最新状态为 `WAITING_APPROVAL` 且版本等于 `expectedVersion` 的 Run：

- `REJECT` 追加 `REJECTED` 并发布事件，不再调度图。
- `APPROVE` 追加携带批准记录的 `RUNNING`，`nextNode` 保持为被中断节点，异步恢复时
  `bypassInterruptAtStart=true`。

服务启动时 `recoverRunningRuns` 查询所有最新 `RUNNING` Checkpoint：普通运行从
`nextNode` 恢复；若最新 Checkpoint 携带 `APPROVE`，只对该起始节点绕过一次中断。
`WAITING_APPROVAL` 不自动恢复，`FAILED` 不自动重试。

每次启动或恢复都由 `GraphRegistry` 创建独立图实例。异步执行任务在 `finally` 中关闭
该图；正常完成、HITL 挂起、拒绝前未调度、执行失败和 Checkpoint 冲突均不得留下图
持有的虚拟线程执行器。`AgentRunService.close()` 只负责停止自身调度器并等待已提交任务
结束，不保存图实例集合。

同一 Run 的并发审批由数据库乐观锁决定；只有一个请求能够追加下一版本，其余请求
抛出 `CheckpointConflictException`。RunService 不缓存权威状态。

## Trace 协议

以下类型位于 `com.agent.core.trace`：

```java
public enum TraceEventType {
    NODE_STARTED,
    NODE_COMPLETED,
    INTERRUPTED,
    APPROVED,
    REJECTED,
    FAILED,
    COMPLETED
}

@FunctionalInterface
public interface TraceEventPublisher {
    void publish(TraceEvent event);
}
```

`TraceEvent` 是带 Jackson 类型标识 `type` 的 sealed interface，具有七个与枚举一一
对应的 record。公共字段为 `eventId`、`runId`、`checkpointVersion`、`occurredAt`；
节点事件包含精确 `nodeName`，中断和审批事件包含中断或审批原因，失败事件包含完整
错误栈。所有集合和文本字段均严格校验并冻结。

发布失败不能回滚已提交 Checkpoint。RunService 保留发布异常的完整堆栈并继续以
数据库状态作为权威结果；Web 适配器必须记录发布失败。该语义避免实时连接故障破坏
持久化运行。

## PostgreSQL 持久化

Flyway 脚本为：

```text
agent-web/src/main/resources/db/migration/V1__create_agent_run_tables.sql
```

`agent_runs` 表：

| 列 | 类型 | 约束 |
|---|---|---|
| `run_id` | `uuid` | 主键 |
| `graph_id` | `varchar(255)` | 非空 |
| `status` | `varchar(32)` | 非空 |
| `latest_version` | `bigint` | 非空且大于等于 0 |
| `created_at` | `timestamptz` | 非空 |
| `updated_at` | `timestamptz` | 非空 |

`agent_checkpoints` 表：

| 列 | 类型 | 约束 |
|---|---|---|
| `run_id` | `uuid` | 外键 `agent_runs(run_id)` |
| `version` | `bigint` | 与 `run_id` 组成主键 |
| `graph_id` | `varchar(255)` | 非空 |
| `status` | `varchar(32)` | 非空 |
| `state_json` | `jsonb` | 非空 |
| `next_node` | `varchar(255)` | 可空 |
| `interrupt_json` | `jsonb` | 可空 |
| `approval_decision` | `varchar(16)` | 可空 |
| `approval_reason` | `text` | 可空 |
| `error` | `text` | 可空 |
| `created_at` | `timestamptz` | 非空 |

建立 `(status, updated_at)` Run 索引和 `(run_id, version desc)` Checkpoint 索引。

`JdbcCheckpointer` 位于 `com.agent.web.persistence`，使用 Spring `JdbcClient` 与
`TransactionTemplate`。`create` 在同一事务插入 Run 与版本 0 Checkpoint。
`append` 在同一事务执行：

```sql
update agent_runs
set latest_version = latest_version + 1,
    status = ?,
    updated_at = ?
where run_id = ? and latest_version = ?
```

更新行数不是 1 时，先精确查询 `run_id` 以区分 not found 与 conflict；成功后插入
新 Checkpoint。状态 JSON 使用注入的 `ObjectMapper` 直接序列化和反序列化
`AgentState`，不手工拼接 JSON。

依赖版本由 Spring Boot 3.3.13 管理：Flyway `10.10.0`、PostgreSQL JDBC
`42.7.7`、Testcontainers `1.19.8`。Flyway 10 同时引入 `flyway-core` 与
`flyway-database-postgresql`。

## REST API

### 创建 Run

```http
POST /api/runs
Content-Type: application/json
```

```json
{
  "graphId": "coder-ops-reviewer",
  "initialState": {
    "messages": [],
    "variables": {},
    "trace": []
  }
}
```

成功返回 HTTP 202 和当前 `RunView`。

### 查询 Run

```http
GET /api/runs/{runId}
```

成功返回 HTTP 200 和最新 `RunView`。

### 审批

```http
POST /api/runs/{runId}/approval
Content-Type: application/json
```

```json
{
  "decision": "APPROVE",
  "expectedVersion": 2,
  "reason": "已核对命令和工作区"
}
```

批准或拒绝均返回 HTTP 202 和新版本 `RunView`。

`RunView` 字段精确为：`runId`、`version`、`graphId`、`status`、`state`、
`nextNode`、`interruptRequest`、`approvalDecision`、`approvalReason`、`error`、
`createdAt`。

错误映射：

- JSON 或字段校验失败：HTTP 400。
- Run 或 graph 不存在：HTTP 404。
- 版本冲突或非 `WAITING_APPROVAL` 状态审批：HTTP 409。
- 未处理基础设施异常：HTTP 500。

错误体使用 Spring `ProblemDetail` 的 `type`、`title`、`status`、`detail`、
`instance`，不增加不稳定字段。

## WebSocket

路径固定为：

```text
/ws/runs/{runId}/trace
```

连接后先通过 `Checkpointer.loadLatest` 发送：

```json
{
  "kind": "SNAPSHOT",
  "run": { }
}
```

随后发送：

```json
{
  "kind": "EVENT",
  "event": { }
}
```

`InMemoryTraceEventBus` 位于 `com.agent.web.trace`，实现
`TraceEventPublisher`，按 `runId` 使用 Reactor `Sinks.Many` 分发。单订阅者缓冲上限
为 256；无法继续消费时关闭该连接并保留服务日志，不影响 Run。

不存在的 Run 在升级后发送关闭状态 4404。客户端断开只清理订阅，不终止 Run。
终态事件发出后完成当前 sink；之后的新连接仍可获得数据库快照，但不重放历史事件。

## Spring 配置

`agent-web` 增加：

- `spring-boot-starter-jdbc`
- `flyway-core`
- `flyway-database-postgresql`
- PostgreSQL JDBC runtime
- Testcontainers PostgreSQL 与 JUnit Jupiter test dependencies

数据库连接从标准 `spring.datasource.url`、`spring.datasource.username`、
`spring.datasource.password` 读取，不提交真实凭据。生产默认值不写入仓库。

应用通过构造器注入配置 `JdbcCheckpointer`、`GraphRegistry`、
`InMemoryTraceEventBus` 和 `AgentRunService`。`ApplicationReadyEvent` 调用一次
`recoverRunningRuns`。应用关闭时关闭 RunService 虚拟线程执行器；每个图实例由对应的
异步执行任务在结束时关闭。

仓库不提供虚构的生产图注册；测试配置注册精确测试 graph。实际部署方必须提供
`Map<String, GraphFactory>` Bean。

## 错误与一致性语义

- 所有异步执行、节点、序列化、数据库和恢复异常保留完整 cause。
- Run 执行错误用 `PrintWriter` 与 `StringWriter` 保存完整堆栈到 `FAILED` Checkpoint。
- Checkpoint 提交失败时不伪造内存成功状态；异步任务失败并记录完整服务日志。
- Trace 推送失败不改变 PostgreSQL 权威状态。
- REST 审批只依据数据库最新版本，不依据进程内对象。
- 恢复使用 Checkpoint 中的精确 `graphId` 与 `nextNode`，不进行名称修正。
- 状态快照采用至少一次节点执行语义，不声明跨外部副作用的 exactly-once。

## 测试策略

### agent-core 单元测试

- `StateGraphInterruptTest`：节点前中断、节点不执行、bypass 只跳过起始节点一次、
  listener 顺序、从指定节点恢复和现有 execute 兼容。
- `CheckpointModelTest`：所有状态组合、字段约束和不可变集合。
- `GraphRegistryTest`：精确 graphId、独立实例、未知或大小写变化拒绝。
- `AgentRunServiceTest`：启动、逐节点 Checkpoint、中断、批准恢复、拒绝终止、并发冲突、
  启动恢复、完整错误栈、虚拟线程和全部 Trace 类型。

### PostgreSQL 集成测试

`JdbcCheckpointerTest` 使用 Testcontainers `postgres:16-alpine`：

- Flyway 真实迁移。
- `AgentState` 与多模态 `ChatMessage` JSONB 往返。
- 版本 0 创建、追加历史、最新状态和按状态查询。
- 两个并发 append 只有一个成功，另一个精确 conflict。
- Run 不存在与事务回滚。

当前环境必须实际启动容器且 skipped 为 0；没有 Docker Engine 的其他环境使用
JUnit assumption 只跳过此数据库集成测试。

### Web 测试

- `RunControllerTest` 使用 `WebTestClient` 验证 202、200、400、404、409 和精确 JSON。
- `RunTraceWebSocketTest` 使用随机端口真实 WebSocket，验证 SNAPSHOT 后 EVENT 顺序、
  runId 隔离、终态完成与 4404。
- `RunLifecycleIntegrationTest` 使用真实 PostgreSQL、真实 REST/WebSocket 与测试图，
  完成 start -> interrupt -> approve -> resume -> completed 闭环。
- `RunRecoveryIntegrationTest` 写入 `RUNNING` Checkpoint 后模拟服务重建，验证从精确
  `nextNode` 恢复；批准 Checkpoint 只绕过该节点一次。

最终执行 `mvn clean verify`，汇总所有 Surefire 报告，确认当前 PostgreSQL
Testcontainers 测试和 WebSocket 测试均执行且 skipped 为 0，并检查测试结束后没有
遗留 PostgreSQL 容器、虚拟线程执行任务或 WebSocket 连接。

## Phase 4 边界

本阶段不实现认证授权、租户隔离、Redis、多实例 Trace、Trace 历史事件表、运行取消、
自动重试、定时补偿、Checkpoint 删除策略、状态模式迁移、任意图上传、危险命令文本
识别、外部工作流引擎或前端页面。

Testcontainers、Maven `target/` 与本地日志继续由根 `.gitignore` 排除。数据库密码、
证书和生产连接配置不得提交。
