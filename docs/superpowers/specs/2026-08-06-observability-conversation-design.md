# Agent4J 可观测性与问答闭环重构设计

## 目标

本次重构同时解决两类问题：

1. 每个 Run 的节点、模型、终端和错误都有可追踪、可审计、可实时展示的事件。
2. 用户问题先经过轻量意图分流；纯问答直接返回 `final_response`，代码任务才进入 Coder/Ops/Reviewer，避免无意义的工作区扫描和沙箱启动。

## 架构

### 日志与 MDC

`agent-core` 直接依赖 `slf4j-api`。`NodeExecutionContext.callWithin` 在虚拟线程中绑定并清理
`runId`、`traceId`、`nodeName` 三个 MDC 字段；`traceId` 使用当前 Run 的精确 UUID 字符串。
`LlmClient` 在每次请求边界临时写入 `modelName`，结束时恢复原 MDC，避免线程复用污染。
`agent-web` 增加 `logback-spring.xml`：控制台 + 按日滚动文件 `logs/agent4j-%d{yyyy-MM-dd}.log`，
保留 30 天并按大小切分。

### LLM 客户端

模型网关使用 Apache HttpClient 5 的 `HttpComponentsClientHttpRequestFactory`，连接超时固定 5 秒，
响应读取超时固定 45 秒。`LlmClient` 记录模型、请求 URL、输入/输出 Token、HTTP 状态码和总耗时；
Socket 超时与 503 使用明确 WARN 日志并包装为 `LlmClientException`，由现有 `ModelRouter` 降级链继续处理。

### 实时事件

保留现有 `TraceEvent` WebSocket 兼容协议，同时增加 `NODE_PROGRESS` 事件，字段包括节点、阶段和摘要。
`NodeExecutionContext.progress` 由 `StateGraph` 转发到 `GraphExecutionListener.onNodeProgress`。
`PlannerNode`、`CoderNode`、`OpsNode` 和 `ReviewerNode` 在关键动作发出摘要；PTY 原始日志继续进入
`RunLogEvent`，前端 Trace 轨同时显示进度，终端轨显示 ANSI 输出。新增 `/api/runs/{runId}/events`
SSE 端点，发送同一套快照/事件帧，供轻量客户端和前端回退使用。

### 问答与代码路由

`PlannerNode` 首先执行分层意图识别。第一层只处理高置信快路径：明确的自然语言问题且不含代码动作词
进入 `chat`；包含代码修改/运行/测试/文件等明确动作词进入 `agent`。未命中快路径时才调用模型做语义
分流，避免把复杂表达误判为闲聊。`chat` 模式只调用 `TaskType.QUICK_CLASSIFICATION` 一轮生成回答，
写入 `final_response`、`planner.response` 和 `planner.route=chat`；`agent` 模式保持现有规划输出并写入
`planner.route=agent`，然后进入工具闭环。

生产图增加条件边：`chat -> END`、`agent -> coder`、`planner.error -> END`。
Coder 失败后不再进入 Ops；生产图将 `coder.error` 路由到终点，`AgentRunService` 根据终态状态中的错误
变量持久化为 `FAILED`，避免产生“Ops 缺少 ops.command”的二次噪声。

### 工作区快照

保留 `capture` 的严格门禁语义，新增供模型 Prompt 使用的有界 `captureForPrompt`：按稳定路径顺序
跳过超出文件数/字节预算的文件，不中止整个 Run。Coder 明确告知模型快照是部分视图，避免大仓库因为
无关文件导致整条链失败。

## 错误与兼容性

- 现有 `GraphExecutionListener`、Trace WebSocket 和 RunLog WebSocket API 保持兼容。
- 新事件采用 Jackson 多态类型 `NODE_PROGRESS`，旧前端忽略未知事件时不影响终态状态查询。
- 模型请求失败仍保留完整异常堆栈；WARN 日志只增加诊断，不吞掉异常。
- `final_response` 在 Run 快照中持久化，前端优先渲染它，缺失时再回退到现有 Planner/Coder/Reviewer 证据。

## 验证门禁

- LlmClient：MDC、Token/耗时日志、503、SocketTimeout 和 URL/状态码测试。
- StateGraph：进度事件和 MDC 清理测试。
- Planner/生产图：问答直达终点、代码任务保留完整链路、Coder 错误不进入 Ops。
- WorkspaceSnapshotService：有界快照不因超限抛错。
- Web：SSE 帧、Trace `NODE_PROGRESS` 解码和最终回答渲染测试。
- 最终执行 `mvn clean package -DskipTests`，再运行受影响模块测试和真实本地 Compose 黑盒请求。
