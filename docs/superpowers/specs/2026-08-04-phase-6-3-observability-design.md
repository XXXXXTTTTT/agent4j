# Phase 6.3 OpenTelemetry、Langfuse 与 Bad Case 归因设计

## 目标与边界

Phase 6.3 在现有强类型 `TraceEvent`、`ModelRouter`、`MemoryManager` 和 PostgreSQL
Checkpoint 之上增加三项能力：

1. 将 Run、节点和模型调用导出为具有精确父子关系的 OpenTelemetry Span；
2. 记录每次模型端点尝试、实际响应模型、Token 用量、降级失败与最终状态；
3. 将执行失败、人工拒绝和审查未通过的 Run 自动归因为 `BAD_CASE` 长期记忆。

本阶段不实现 Benchmark、`pass^k`、TTFT 统计、Trace 查询 REST API、前端 Trace
瀑布图、Langfuse 部署、OpenTelemetry Collector 部署或任意真实密钥配置。这些内容分别属于
Phase 6.4、部署环境或后续产品工作。

`agent-core` 继续保持框架无关：它只定义观测端口和节点执行上下文，不依赖
OpenTelemetry SDK、Spring 或 Langfuse。OpenTelemetry SDK 与 OTLP 导出适配位于
`agent-web`，Bad Case 到长期记忆的适配位于 `agent-rag`。

## 依赖与官方协议

- Java 固定为 21，禁止启用预览特性。
- OpenTelemetry BOM 固定为 `1.64.0`。Maven Central 在 2026-07-10 发布该版本，
  Phase 6.3 设计时它是仓库元数据中的最新 release。
- 使用 `opentelemetry-api`、`opentelemetry-sdk` 和
  `opentelemetry-exporter-otlp`；测试使用 `opentelemetry-sdk-testing`。
- 只使用 OTLP over HTTP/protobuf。Langfuse 官方文档明确说明 gRPC 尚不受支持。
- 配置项接收完整 traces endpoint，不自行拼接路径。Langfuse Cloud EU 的官方示例为
  `https://cloud.langfuse.com/api/public/otel/v1/traces`，本地部署使用对应完整
  `/api/public/otel/v1/traces` 地址。
- OTLP 请求必须包含调用方提供的精确 `Authorization` Header，以及固定
  `x-langfuse-ingestion-version: 4` Header。
- 仓库不得保存 Public Key、Secret Key、Basic Auth 明文或 Base64 结果。

官方协议依据：

- `https://langfuse.com/integrations/native/opentelemetry.md`
- `https://github.com/open-telemetry/semantic-conventions-genai`
- `https://repo1.maven.org/maven2/io/opentelemetry/opentelemetry-bom/maven-metadata.xml`

## 方案比较

### 方案一：核心端口加 Web OpenTelemetry 适配器

`agent-core` 暴露模型观测端口并在节点虚拟线程中绑定精确
`NodeExecutionContext`；`agent-web` 同时消费生命周期 `TraceEvent` 和模型观测回调，
建立 Run、Node、Generation Span；`agent-rag` 消费终态并写入 Bad Case。

优点是保持核心框架无关，模型降级和 Token 可精确挂到实际节点，并可在测试中使用
`InMemorySpanExporter` 完整验证。缺点是需要维护一个按 `runId` 隔离的活动 Span 注册表。

### 方案二：OpenTelemetry 直接进入 agent-core

在 `AgentRunService`、`StateGraph` 和 `ModelRouter` 中直接使用 OpenTelemetry API。
实现代码较短，但会让核心引擎绑定第三方观测 API，违背项目的 No Framework Lock-in
原则，也使无观测环境下的核心测试承担不必要依赖。

### 方案三：仅把 TraceEvent 后处理成 Span

只在 `agent-web` 将现有事件转换为 Span，不修改节点上下文和 `ModelRouter`。改动最少，
但模型调用发生在另一层，无法可靠关联 Run、节点、端点降级尝试和 Token 用量。

采用方案一。

## agent-core 观测协议

### NodeExecutionContext 当前上下文

`NodeExecutionContext` 增加：

```java
public static Optional<NodeExecutionContext> current()
```

`StateGraph.executeNode` 在实际节点虚拟线程中使用包内方法绑定上下文，并在 `finally`
中移除。Java 21 的 `ScopedValue` 仍是预览 API，本项目不开启预览特性，因此使用
`ThreadLocal<NodeExecutionContext>`；它只存在于每任务独占的虚拟线程中，禁止跨线程共享，
并由测试验证正常返回和异常返回后均不残留。

### 模型观测端口

新增包 `com.agent.core.observability`：

```java
public record ModelCallStart(
        Optional<NodeExecutionContext> nodeContext,
        TaskType taskType,
        String endpointName,
        String requestedModel) {}

public record ModelUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens) {}

public record ModelCallSuccess(
        Optional<String> responseModel,
        Optional<ModelUsage> usage) {}

public interface ModelCallSpan extends AutoCloseable {
    void succeed(ModelCallSuccess success);
    void fail(Throwable failure);
    @Override void close();
}

@FunctionalInterface
public interface ModelCallObserver {
    ModelCallSpan start(ModelCallStart start);
    static ModelCallObserver noop();
}
```

所有 record 执行非空、非空白和非负数校验；`ModelUsage.totalTokens` 必须等于
`promptTokens + completionTokens`。`Optional` 字段自身不得为 null。

`ModelRouter` 保留现有 `ModelRouter(Map<TaskType, List<ModelEndpoint>> routes)` 构造器，
并新增显式构造器：

```java
public ModelRouter(
        Map<TaskType, List<ModelEndpoint>> routes,
        ModelCallObserver modelCallObserver)
```

旧构造器精确委托 `ModelCallObserver.noop()`。每个端点尝试各创建一个
`ModelCallSpan`，成功时写入响应模型和 `LlmClient.Usage`，失败时写入完整异常，最后关闭。
因此同一次路由发生降级时，失败端点与成功端点是两个独立 Generation Span。

观测实现异常不能改变模型路由结果。`ModelRouter` 使用 `System.Logger` 记录完整异常并降级为
noop span；它不会吞掉模型端点异常，端点异常仍进入现有 `ModelRoutingException` suppressed
链。

## OpenTelemetry Span 模型

新增 `com.agent.web.observability.OpenTelemetryRunTracePublisher`，同时实现
`TraceEventPublisher`、`ModelCallObserver` 和 `AutoCloseable`。它持有线程安全的
`ConcurrentMap<UUID, RunSpanState>`，每个 `runId` 最多存在一个活动 Run 段和一个活动
Node Span。

### Run Span

- Span 名称：`agent.run`
- `langfuse.trace.name = agent.run`
- `langfuse.session.id = runId`
- `agent.run.id = runId`
- `agent.checkpoint.version = checkpointVersion`
- `langfuse.trace.metadata.checkpoint_version = checkpointVersion` 的十进制字符串

首次收到一个 Run 段的事件时创建 root Span。`INTERRUPTED` 会结束当前 Run 段；恢复后的
`APPROVED`/`NODE_STARTED` 创建新 root Span，但所有段共享同一个
`langfuse.session.id = runId`，因此跨进程恢复仍可在 Langfuse 中按 Run 聚合，而不伪造或
持久化 OpenTelemetry trace id。

`COMPLETED` 正常结束 root Span；`FAILED` 将活动 Node 和 root 标记为 ERROR，记录异常文本
并结束；`REJECTED` 记录人工拒绝事件后结束。无序的重复开始、节点名称不一致或缺失结束
事件会抛出精确 `IllegalStateException`，由现有 `AgentRunService.publish` 完整记录，禁止静默
修正事件顺序。

### Node Span

- Span 名称：`agent.node <nodeName>`
- 父 Span：当前 Run Span
- `agent.node.name = nodeName`
- `agent.checkpoint.version = checkpointVersion`
- 完成时增加 `agent.next_node = nextNode`

`NODE_STARTED` 使用事件 `occurredAt` 作为开始时间，`NODE_COMPLETED` 使用事件
`occurredAt` 结束。`FAILED` 会关闭仍活动的 Node Span并记录失败。

### Generation Span

- Span 名称：`chat <requestedModel>`
- 父 Span：`ModelCallStart.nodeContext.runId/nodeName` 对应的活动 Node Span；不存在节点上下文
  时创建无父 Generation Span。
- `langfuse.observation.type = generation`
- `gen_ai.operation.name = chat`
- `gen_ai.request.model = requestedModel`
- `gen_ai.response.model = responseModel`，仅在响应提供非空值时写入
- `gen_ai.usage.input_tokens = promptTokens`
- `gen_ai.usage.output_tokens = completionTokens`
- `agent.model.total_tokens = totalTokens`
- `agent.model.endpoint = endpointName`
- `agent.model.task_type = TaskType.name()`

失败调用记录异常并设置 ERROR。成功响应未包含 `usage` 时不虚构 Token 数值。

## Langfuse OTLP 配置

新增 `ObservabilityProperties`，精确前缀为 `agent.observability`：

- `enabled`：默认 `false`
- `service-name`：默认 `agent-runtime-system`
- `otlp-traces-endpoint`：启用时必须为绝对 HTTP/HTTPS URI
- `authorization`：启用时必须为非空白完整 Header 值，例如由环境变量提供的
  `Basic <base64>`
- `export-timeout`：默认 `10s`，必须大于 0

`application.properties` 只引用环境变量：

```properties
agent.observability.enabled=${AGENT_OBSERVABILITY_ENABLED:false}
agent.observability.service-name=${AGENT_OBSERVABILITY_SERVICE_NAME:agent-runtime-system}
agent.observability.otlp-traces-endpoint=${AGENT_OBSERVABILITY_OTLP_TRACES_ENDPOINT:}
agent.observability.authorization=${AGENT_OBSERVABILITY_AUTHORIZATION:}
agent.observability.export-timeout=${AGENT_OBSERVABILITY_EXPORT_TIMEOUT:10s}
```

关闭时不创建 exporter，不发起网络连接，并提供 noop `ModelCallObserver`。启用时使用
`OtlpHttpSpanExporter`、`BatchSpanProcessor` 和 `SdkTracerProvider`。应用关闭时先
`forceFlush`，再 `shutdown`；flush/export 错误完整记录，不阻止其他 Spring Bean 关闭。

## Trace 发布组合

`RunLifecycleEventPublisher` 改为持有不可变的 `List<TraceEventPublisher>`。每个事件必须按
注入顺序发布给全部发布器；某个发布器失败时继续执行其余发布器，最后抛出第一个异常并将
其余异常作为 suppressed。终态日志流清理仍放在 `finally` 中。

生产组合顺序固定为：

1. `InMemoryTraceEventBus`，保证 WebSocket 低延迟；
2. 启用时的 `OpenTelemetryRunTracePublisher`；
3. 存在 `RunBadCaseAttributor` Bean 时的 Bad Case 归因。

## Bad Case 自动归因

### 判定范围

新增 `com.agent.rag.memory.RunBadCaseAttributor`，实现 `TraceEventPublisher`。它只处理：

- `TraceEvent.Failed`；
- `TraceEvent.Rejected`；
- `TraceEvent.Completed` 且最终状态满足下列任一精确条件：
  - `reviewer.approved` 等于 `false`；
  - `ops.timedOut` 等于 `true`；
  - `ops.exitCode` 是十进制整数且不等于 0；
  - `planner.error`、`coder.error`、`ops.error`、`reviewer.error` 任一存在且非空白。

`ops.exitCode` 存在但不是十进制整数时抛出 `IllegalArgumentException`，禁止猜测其含义。
成功完成且不满足任何条件的 Run 不调用 MemoryManager。

### Scope 与证据

归因必须从最终 `AgentState` 精确读取：

- `planner.repositoryId`
- `planner.userId`
- `planner.task`

缺少任一键时抛出包含精确键名的 `IllegalArgumentException`，不得写入无 scope 记忆。

传给模型的证据只包含固定 allowlist，禁止序列化整个状态或密钥：

- Run ID、Graph ID、Checkpoint version、终态类型；
- `planner.task`；
- `coder.updatedFiles`、`coder.error`；
- `ops.exitCode`、`ops.stdout`、`ops.stderr`、`ops.timedOut`、`ops.error`；
- `reviewer.approved`、`reviewer.summary`、`reviewer.feedback`、`reviewer.error`；
- `FAILED` 的完整错误或 `REJECTED` 的人工理由。

单个字段按 UTF-16 code unit 截断到 4,000 字符，总证据不得超过 `MemoryCapture` 的
20,000 字符上限。截断规则由独立测试覆盖。

### BAD_CASE 类型约束

`MemoryManager` 新增：

```java
public List<MemoryEntry> captureBadCases(MemoryCapture capture)
```

它复用现有提取、hash、embedding 和事务 upsert 流程，但在任何 embedding 或数据库写入
之前验证所有 `MemoryDraft.type()` 都严格等于 `MemoryType.BAD_CASE`。错误类型导致整批失败，
禁止部分写入。`RunBadCaseAttributor` 在证据前加入“只返回 BAD_CASE”协议说明，并调用该方法。

归因发生在权威终态 Checkpoint 已写入之后。归因异常由发布链完整记录，但不能回滚或改写
Run 终态；这保持 PostgreSQL Checkpoint 为运行状态 SSOT，并避免观测系统故障污染业务结果。

## 并发、恢复与资源治理

- 活动 Span 注册表按 `runId` 隔离，使用原子 map 操作，禁止跨 Run 共享可变 Span 状态。
- OpenTelemetry SDK 自己管理 BatchSpanProcessor 工作线程；Agent 节点和模型请求仍使用
  Java 21 虚拟线程。
- NodeExecutionContext 必须在节点虚拟线程 `finally` 清除，异常路径同样清除。
- `INTERRUPTED` 结束当前 Run 段，防止人工审批期间 Span 永久悬挂。
- `close()` 必须结束并标错仍活动的 Span，再 flush/shutdown exporter。
- 测试结束后不得残留 Testcontainers、受管 Docker 容器、Maven、WinPTY 或 OTLP 测试服务。

## 测试门禁

### agent-core

- 节点虚拟线程内可读取精确 `NodeExecutionContext`，正常和异常后均无 ThreadLocal 残留；
- 每个模型端点尝试创建独立 span，降级顺序不变；
- 成功记录实际响应模型与 Token，缺失 usage 不填假数据；
- 失败保留原模型异常和 observer 异常日志，不改变既有 suppressed 链。

### agent-web

- 使用 `InMemorySpanExporter` 验证 Run -> Node -> Generation 父子关系和全部精确属性；
- 中断/恢复产生共享 `langfuse.session.id` 的两个 Run 段；
- 失败关闭活动 Node/Run Span并设置 ERROR；
- 使用本机临时 HTTP 服务接收真实 OTLP HTTP/protobuf，请求路径必须是测试配置的完整路径，
  并验证 `Authorization` 与 `x-langfuse-ingestion-version: 4`；
- 关闭配置不创建网络请求；无效启用配置启动失败并指出精确配置项；
- Trace 发布器失败不阻止 WebSocket 发布和终态日志清理。

### agent-rag

- FAILED、REJECTED、审查拒绝、Ops 超时、非零退出码和节点错误分别触发归因；
- 成功 Run 不触发归因；
- scope 缺失、非法退出码、非 BAD_CASE 草稿整批失败且不写数据库；
- 证据 allowlist 和截断上限得到验证；
- 真实 PostgreSQL `pgvector/pgvector:pg16` 验证自动归因写入、scope 隔离与去重。

### 全链路

构建一条真实 `AgentRunService -> StateGraph -> ModelRouter -> TraceEventPublisher ->
OpenTelemetryRunTracePublisher` 流程，断言节点拓扑、模型 usage 和终态一致。测试模型端点使用
本机模拟 OpenAI HTTP 服务，不访问真实模型 API；Langfuse 使用本机 OTLP 接收器，不提交或
请求真实密钥。

最终门禁：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core,agent-rag,agent-web -am clean verify
mvn clean verify
git diff --check
```

同时验证 `java -version` 为 21、Docker 集成测试无 skip、npm audit 为 0、禁止依赖扫描未发现
LangChain4j/LangGraph4j、Git 工作树干净且不存在本阶段资源残留。

## 提交策略

设计、计划、核心观测协议、OpenTelemetry 适配、Bad Case 归因、全链路测试和工程复盘分别
形成原子 Conventional Commits，scope 必填。实现完成后本地 fast-forward 合并回
`master`，并只清理 Phase 6.3 worktree 与功能分支。
