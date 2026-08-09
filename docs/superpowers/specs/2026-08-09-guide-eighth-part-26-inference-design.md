# 第八篇第 26 章 Inference Framework 工程化设计

## 目标与教程依据

为 Agent4J 已有的 OpenAI 兼容 `LlmClient`、`ModelRouter`、Resilience4j 熔断与顺序降级补齐推理端点能力契约、端点级并发/速率准入、流式背压指标和可移植模型服务描述，使云端网关、Ollama、vLLM、SGLang 或 TGI 能通过相同核心契约接入，而不把任何具体推理服务器 SDK 引入 `agent-core`。

项目总矩阵 `2026-08-07-guide-4-26-modernization-design.md` 将本项固定为第 26 章。教程参考仓库 `fuzhengwei/ai-agent-guide` 的本地提交 `91066e4` 当前在 `js/main.js` 中把同一主题显示为第 25 章，并由 `chapters/ch20-inference-framework.html` 承载内容。本文继续沿用项目既有第 26 章编号，不重排已完成里程碑。

教程中与 Agent4J 直接相关的工程结论是：

- 主流推理服务通过 OpenAI Chat Completions 协议保持调用方可移植。
- 不同端点对流式输出、工具调用和视觉输入的能力并不相同，路由前必须显式描述和校验。
- 推理吞吐依赖并发管理，调用方也必须设置本地准入预算，避免无限排队把延迟扩散到整个 Agent 图。
- 生产观测必须包含 TTFT、吞吐与成功率；对当前同步 SSE 客户端，还必须量化消费者处理造成的读取背压。

## 路线选择

### 采用：扩展现有路由端点

保留 `LlmClient` 作为 OpenAI Chat Completions 协议适配器，保留 `ModelRouter` 的 TaskType 路由、熔断和 fallback 语义。`ModelEndpoint` 增加不可变协议、能力集合和独立准入控制器，并保留现有四参数构造器作为源码兼容入口。

这条路线复用已经过真实模型 EDD 验证的协议和错误模型，变更面集中在 `agent-core/llm` 与 `agent-web/config`，不会引入推理服务器运行时依赖。

### 不采用：为 Ollama、vLLM、SGLang 分别实现客户端

各服务目前都提供 OpenAI 兼容接口。分别实现客户端会复制鉴权、SSE、错误处理和日志逻辑，并让核心绑定具体厂商。

### 不采用：只在反向代理配置限流

网关限流无法约束单个 Agent 进程内的虚拟线程排队，也无法让 `ModelRouter` 在主端点预算耗尽时立即选择下一端点，因此不能形成进程内延迟隔离。

## 核心契约

### 可移植服务描述

- `InferenceProtocol`：当前只声明 `OPENAI_CHAT_COMPLETIONS`。
- `InferenceCapability`：精确枚举 `CHAT_COMPLETIONS`、`STREAMING`、`TOOL_CALLING`、`VISION_INPUT`。
- `InferenceServiceContract`：不可变 record，包含端点名、模型名、协议和能力集合；集合在构造时冻结。
- `ModelEndpoint.serviceContract()`：返回不含 API Key 的可审计契约。
- 现有四参数 `ModelEndpoint` 构造器固定声明全部四项能力，确保已有 Core、RAG、Web 与 EDD 调用方在迁移期间不发生行为变化；`ModelGatewayConfiguration` 改用完整构造器和 `.env` 显式能力，不依赖该兼容默认值。

`ModelRouter.complete(...)` 始终要求 `CHAT_COMPLETIONS`；请求含工具时额外要求 `TOOL_CALLING`；`TaskType.VISION` 额外要求 `VISION_INPUT`。新增的 `ModelRouter.stream(...)` 再额外要求 `STREAMING`。能力不匹配发生在 HTTP 调用和熔断器之前。

### 端点级准入预算

- `InferenceBudget`：不可变 record，精确包含 `maxConcurrentRequests`、`maxRequestsPerMinute` 和 `queueTimeout`。
- `InferenceAdmissionController`：每个 `ModelEndpoint` 独占一个实例；使用公平 `Semaphore` 约束并发，使用注入的 `Clock` 维护最近一分钟的已准入请求时间。
- `InferencePermit`：成功准入后返回的 `AutoCloseable` 许可，关闭时恰好释放一次并发额度。
- `InferenceAdmissionException`：携带强类型 `InferenceRejectionReason.CONCURRENCY_LIMIT` 或 `RATE_LIMIT`。
- `InferenceAdmissionSnapshot`：暴露当前活跃请求、最近一分钟已准入请求和两类拒绝累计值。

准入顺序固定为先等待并发许可，再检查速率预算；速率拒绝时立即归还并发许可。等待中断时恢复线程中断标志并抛出强类型异常。能力或准入失败允许 `ModelRouter` 继续 fallback，但不计入对应端点的 CircuitBreaker 失败率。

### 流式背压指标

`LlmClient.stream(...)` 保持消费者在 SSE 读取虚拟线程内同步执行，因此消费者处理自然对上游读取施加背压，不引入无界队列。方法返回不可变 `StreamingMetrics`，精确包含：

- HTTP 状态码；
- 首个有效 chunk 的 TTFT；
- 完整流持续时间；
- chunk 数；
- 所有消费者回调累计耗时；
- 单次消费者回调最大耗时。

TTFT 在 JSON chunk 解析完成、调用消费者之前记录。空流使用空 `Optional<Duration>`，不以 `0` 或负数伪装首 token。成功日志增加上述字段；失败仍保留现有异常链日志。

`ModelRouter.stream(...)` 返回 `RoutedStreamingCompletion`，包含实际端点、模型和 `StreamingMetrics`。端点失败仍按原有列表顺序降级。

## Web 配置

`ModelGatewayProperties` 新增全局端点预算配置：

| Spring 属性 | 环境变量 | 默认值 |
| --- | --- | --- |
| `agent.llm.max-concurrent-requests` | `AGENT_LLM_MAX_CONCURRENT_REQUESTS` | `8` |
| `agent.llm.max-requests-per-minute` | `AGENT_LLM_MAX_REQUESTS_PER_MINUTE` | `120` |
| `agent.llm.queue-timeout` | `AGENT_LLM_QUEUE_TIMEOUT` | `2s` |

同时新增四组显式能力配置，值必须由 `InferenceCapability` 精确枚举组成：

| Spring 属性 | 环境变量 | 默认值 |
| --- | --- | --- |
| `agent.llm.code-capabilities` | `AGENT_LLM_CODE_CAPABILITIES` | `CHAT_COMPLETIONS,STREAMING,TOOL_CALLING` |
| `agent.llm.vision-capabilities` | `AGENT_LLM_VISION_CAPABILITIES` | `CHAT_COMPLETIONS,STREAMING,VISION_INPUT` |
| `agent.llm.quick-classification-capabilities` | `AGENT_LLM_QUICK_CLASSIFICATION_CAPABILITIES` | `CHAT_COMPLETIONS,STREAMING` |
| `agent.llm.fallback-capabilities` | `AGENT_LLM_FALLBACK_CAPABILITIES` | `CHAT_COMPLETIONS` |

配置类为每个路由项创建独立准入控制器。fallback 默认不虚构工具和视觉能力；部署方确认模型支持后必须通过 `.env` 显式声明。

## 错误与降级语义

1. 能力不匹配：包装为 `ModelEndpointException` 后尝试下一端点，不调用 HTTP，不改变熔断器状态。
2. 并发或速率拒绝：包装后尝试下一端点，不改变熔断器状态；所有端点均拒绝时由 `ModelRoutingException` 按路由顺序保留 suppressed 异常。
3. HTTP、响应结构或已有熔断失败：继续沿用 CircuitBreaker 统计与顺序 fallback。
4. 图级 `ExecutionBudgetExceededException`：仍立即上抛，不降级。
5. 流式消费者异常：视为该端点流调用失败，释放准入许可并进入 fallback；异常链完整保留。

## 测试与验收

1. `InferenceServiceContractTest` 覆盖集合冻结、非法字段和四参数旧构造器兼容。
2. `InferenceAdmissionControllerTest` 使用可控 `Clock` 覆盖并发超时、速率窗口恢复、许可幂等释放和快照。
3. `ModelRouterTest` 先写红灯，覆盖能力预检、预算 fallback、不污染熔断器、流式 fallback 与实际端点结果。
4. `LlmClientTest` 使用真实 `MockRestServiceServer` SSE 响应和可控纳秒时钟精确断言 TTFT、chunk 数与消费者背压指标。
5. `ModelGatewayPropertiesTest` 与配置测试覆盖环境映射、默认值、非法预算和能力声明。
6. `InferenceFrameworkEddTest` 生成确定性第 26 章报告，验证协议可移植性、预算、能力和指标契约；本章不要求本机 GPU 或启动具体推理服务器。
7. 使用显式 JDK 21 执行相关模块测试、全量 Java 测试、跳过前端重建的完整 Maven 打包和 `git diff --check`。

## 非目标

- 不下载或托管模型权重。
- 不启动 Ollama、vLLM、SGLang 或 TGI。
- 不实现 GPU 调度、Continuous Batching、PagedAttention、量化或 KV Cache。
- 不改变 Agent 图、Planner/Coder/Ops/Reviewer 的业务转移逻辑。
- 不引入 LangChain4j、LangGraph4j 或推理服务器 Java SDK。
