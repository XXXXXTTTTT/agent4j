# EDD、Planner 路由与审计日志设计

## 背景

生产对话在连续追问时出现 `任务路由模型必须精确返回 chat 或 agent`，同时容器部署下的滚动日志没有宿主机可见的持久化目录。本设计修复路由协议的容错边界，并把真实模型评测接入现有 `agent-eval`。

## 教程依据

参考 `fuzhengwei/ai-agent-guide` 的 `chapters/ch17-evaluation.html`，页面标题为“第23章 Agent 评估与可观测性”。本项目采用其中的基准任务集、轨迹/失败归因、TTFT 与日志/指标/追踪分层观测原则；自然语言最终回答不做整段字符串硬等值断言。

## 日志与部署

`agent-web` 的 Logback 同时写控制台和按天滚动文件，默认目录为 `logs`，归档保留 30 天。Spring 配置键为 `agent.logging.directory`，环境变量为 `AGENT_LOG_DIR`。Compose 将宿主机 `${AGENT_LOG_HOST_DIR:-./logs}` 绑定到容器 `/app/logs`，并把 `AGENT_LOG_DIR` 精确设置为 `/app/logs`；宿主机日志目录由 `.gitignore` 排除。

日志包含 `runId`、`traceId`、`nodeName`、`modelName` MDC 字段。Planner 记录路由模型输出的安全截断摘要和规范化结果，不记录 API Key 或完整用户 Prompt。

## Planner 路由协议

模型协议仍是精确路由值 `chat` 或 `agent`。客户端只接受以下可证明的格式：精确值、包裹在 Markdown 代码围栏中的精确值、JSON 对象中的字符串 `route` 字段，以及以路由值开头并由空白/标点分隔的解释文本。无法证明路由的自然语言请求安全回退到 `chat`，并记录 WARN；即使模型明确返回 `agent`，没有明确工具/代码动作词的自然语言任务也降级为 `chat`，避免历史上下文把天气规划误带入代码链。网络、HTTP 或响应结构异常仍保留完整异常并进入 `planner.error=FAILED`。

含有代码动作词的请求先走 `agent` 快路径，不调用语义分流。纯问答只调用一次快速问答模型并写入 `final_response`。

## EDD

新增 `LlmEddTest`，从现有 `AGENT_LLM_BASE_URL`、`AGENT_LLM_API_KEY`、`AGENT_LLM_QUICK_CLASSIFICATION_MODEL`、`AGENT_LLM_CODE_MODEL` 和 `AGENT_LLM_FALLBACK_MODEL` 读取真实 OpenAI 兼容端点。默认 Maven 验证不触发外部调用；显式设置 `AGENT_LLM_ENABLED=true` 后运行 `mvn -pl agent-eval -Dtest=LlmEddTest -DfailIfNoTests=false test`。

EDD 场景覆盖模型身份问答、无车出游规划、连续追问记忆、代码修改意图和异常路由输出。每个场景报告 route、终态、总耗时、TTFT（可用时）、节点轨迹、错误摘要和结构化门禁结果，报告写入 `target/edd/`，不提交真实回答或密钥。

## 验收门禁

先运行 Planner 单元测试和 agent-eval EDD；再使用 JDK 21 执行 `mvn clean verify`。外部模型未开启时 EDD 明确跳过，开启后必须实际请求配置的端点并生成 JSON 报告。
