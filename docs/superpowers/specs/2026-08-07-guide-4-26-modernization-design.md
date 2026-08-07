# Agent 教程第 4–26 章工程化增强总设计

## 1. 背景与目标

本设计以 `fuzhengwei/ai-agent-guide` 当前 `js/main.js` 的目录映射为唯一章节依据，覆盖第二篇至第八篇、显示编号第 4–26 章。目标不是把教程示例迁入 Agent4J，而是逐章提炼可验证的工程实践，与现有 Java 21 自研图引擎、PostgreSQL 权威状态、Docker/PTY 沙箱、Playwright、RAG、OpenTelemetry 和 EDD 能力对照后增量补强。

Agent4J 继续遵守以下边界：

- 不引入 LangChain4j、LangGraph4j 或其他第三方向导框架。
- `AgentState` 继续保持不可变 record 语义，跨节点只传递精确状态键。
- 图调度、模型阻塞 I/O、终端和浏览器等待继续使用 Java 21 虚拟线程。
- PostgreSQL 继续作为 Run、会话、Checkpoint、RAG 和长期记忆的唯一权威持久化源。
- 教程中的 Python、Node.js 和特定厂商 SDK 只作为协议与工程模式参考，不直接决定本项目类型和包结构。
- 所有新增行为必须先有失败测试，再实现最小生产代码，并通过模块测试、集成测试和适用的 EDD 门禁。

## 2. 路线选择

### 2.1 采用路线：按篇独立闭环

第二至第八篇分别建立独立规格、实施计划、测试门禁和 Conventional Commit。每篇先确认现有能力，再只实现能形成生产闭环的缺口；已有能力以补充验证和安全门禁为主。

这条路线保留现有提交历史和模块边界，任何里程碑结束时都得到可运行、可回归的软件，而不是长期处于跨模块半成品状态。

### 2.2 不采用路线：一次性平台重写

一次性重写可以统一抽象，但会同时改动模型协议、状态图、持久化、沙箱、前端和评测，回归面超过单个里程碑可验证范围，也会削弱 Phase 1–6 已建立的证据链。

### 2.3 不采用路线：逐章机械移植

教程章节包含 LangGraph、低代码平台、Python 工具和模型服务示例。机械移植会造成框架锁定和重复能力。本项目只吸收强类型协议、上下文治理、安全策略、可观测性和评测方法。

## 3. 当前基线

### 3.1 已具备的生产能力

- `StateGraph` 已支持虚拟线程节点执行、普通边、条件边、最大步数、中断、恢复和节点过程事件。
- `AgentRunService` 已把节点边界、Checkpoint、失败堆栈和 Trace 连接为持久化 Run 生命周期。
- `PlannerNode` 已支持短期对话、长期记忆注入、问答/代码链分流和 `final_response`。
- `ModelRouter` 已按 `TaskType` 路由模型，并使用 Resilience4j 熔断与降级链。
- `AstService`、`WorkspaceSnapshotService`、`SandboxTerminalService` 和 Playwright 已组成代码、终端和浏览器执行能力。
- `JdbcConversationContextProvider` 已按完整用户/助手轮次加载 PostgreSQL 会话历史。
- `HybridRagRetriever` 已实现向量、BM25 和符号三路融合；`CodebaseChunker` 已实现 Java AST 父子切片。
- `MemoryManager` 已实现长期记忆提取、SHA-256 去重、混合召回和 Bad Case 归因。
- `agent-web` 已提供工作区权限、会话、审批、终端、Trace 和前端工作台。
- `agent-eval` 已提供版本化任务集、`pass^k`、TTFT、报告和真实模型 EDD 开关。

### 3.2 已确认的横向缺口

- Prompt 仍以节点静态字符串存在，没有版本、指纹、静态/动态分区和审计协议。
- 会话上下文只按字符和完整轮次裁剪，没有 token 估算、优先级保护、摘要端口和渐进压缩。
- Planner 仍以 `chat`/`agent` 文本和动作词判断，没有强类型任务类别、复杂度、复合意图与决策证据。
- `StateGraph` 只有步数上限，没有总时长、空闲时长、token 和重复进展预算，也没有通用 Hook 链。
- `agent-core/tool` 没有生产 `ToolRegistry`；MCP、Skills 和工具权限协议尚未实现。
- 长期记忆排序没有重要度、访问频率和时间衰减，静态规范与动态经验使用同一生命周期。
- RAG 没有查询改写、HyDE、rerank、检索预算或知识文件层级编译。
- Docker/PTY 已有超时清理，但没有统一的权限决策记录与 Violation Store。

## 4. 第 4–26 章能力矩阵

状态定义：`已有` 表示生产代码和测试已经覆盖章节核心实践；`部分` 表示已有可复用基础但仍缺少关键闭环；`缺失` 表示没有发现对应生产实现。

| 篇/章 | 教程主题 | 当前状态与精确证据 | Agent4J 增强决策 |
|---|---|---|---|
| 第二篇 / 4 | Prompt Engineering | 部分：`PlannerNode`、`CoderNode`、`ReviewerNode` 有静态系统 Prompt；`ModelRequest` 支持消息、工具、工具选择和温度 | 新增版本化 `PromptCatalog`、`PromptTemplate`、静态/动态分区、SHA-256 指纹和渲染审计；不引入外部 Prompt 平台 |
| 第二篇 / 5 | Context Engineering | 部分：`ConversationContext` 与 `JdbcConversationContextProvider` 按完整轮次、字符数和轮次数裁剪 | 新增 token 估算、消息优先级、受保护消息、滑动窗口、摘要端口与三级压缩结果；当前用户指令和工具错误不得被裁掉 |
| 第二篇 / 6 | ReAct Pattern | 部分：`StateGraph` 已有 Planner→Coder→Ops→Reviewer 修复循环、最大步数和过程事件 | 新增执行预算、重复进展检测和停止原因；保留节点状态机，不要求公开模型隐藏思维链，只发布关键动作摘要 |
| 第二篇 / 7 | Memory | 部分：`MemoryManager` 已有三种类型、去重、混合召回和 Bad Case 捕获 | 为记忆增加重要度、访问计数、最后访问时间和时间衰减；静态架构规则不衰减，动态偏好与 Bad Case 使用明确生命周期 |
| 第二篇 / 8 | Intent Router | 部分：`PlannerNode` 有快路由、语义路由和安全问答回退；`TaskType` 只负责模型选择 | 新增强类型 `TaskDecision`，精确记录 route、taskKind、complexity、requiredCapabilities 和理由摘要；支持一个请求包含问答与工具动作 |
| 第二篇 / 9 | Loop / Runtime / Sandbox | 部分：图循环、虚拟线程、Docker/PTY 超时、Checkpoint 恢复已存在 | 新增总时长、节点空闲时长、token、步数和无进展联合预算；把超限原因写入状态、Trace 和审计日志 |
| 第二篇 / 10 | Harness | 部分：文件、沙箱、项目知识、Web、上下文和编排分散存在；Hooks、权限违规存储缺失 | 新增确定性 Hook 链和权限决策端口；先覆盖节点前后、工具前后、失败和预算耗尽事件，再由后续篇章接入工具/MCP/Skills |
| 第三篇 / 11 | RAG | 部分：父子切片、Java AST、pgvector、BM25、符号融合已有 | 新增查询改写端口、HyDE 可选阶段、rerank 端口和 token 检索预算；每阶段保留分数与降级证据 |
| 第三篇 / 12 | LLM Wiki | 缺失：没有 AGENTS/CLAUDE/SOUL 等层级知识编译服务 | 新增 `ProjectKnowledgeCompiler`，按仓库根、目录和文件层级加载明确支持的知识文件，生成带来源哈希的项目上下文；热重载以内容哈希失效实现 |
| 第四篇 / 13 | Tools | 缺失：`agent-core/tool` 只有包说明 | 新增强类型 `ToolDefinition`、`ToolCall`、`ToolResult`、`ToolRegistry`，执行前做 JSON Schema、角色和权限校验，完整保留异常栈 |
| 第四篇 / 14 | MCP | 缺失：没有 MCP 协议适配器 | 在工具注册中心之上新增 MCP 客户端端口和协议适配层；MCP 工具必须经过相同权限、审批、超时和审计，不旁路核心治理 |
| 第四篇 / 15 | Skills | 缺失：没有可发现、可版本化的 Skill 清单 | 新增只读 Skill 清单、版本、触发条件和 Prompt 片段协议；Skill 只编排已注册工具，不允许任意反射调用生产类 |
| 第四篇 / 16 | CLI Capability | 部分：`SandboxTerminalService`、Docker 和 PTY 已能执行 Bash 并捕获 ANSI | 新增强类型命令意图、命令风险分级、工作区边界和审批决策；禁止把自然语言直接拼接成 Shell 字符串 |
| 第五篇 / 17 | Multi-Agent | 缺失：当前是单图多节点，没有 Agent 间 handoff 协议 | 新增 `AgentDescriptor`、`AgentHandoff` 和有界子运行；上下文传递显式选择 fork 或 fresh，结果合并必须校验状态键所有权 |
| 第五篇 / 18 | LangGraph | 已有：`StateGraph` 已提供自研图、条件路由、中断、Checkpoint 和恢复 | 不引入 LangGraph；补充子图、循环停止原因和图拓扑验证，以本项目测试证明对应能力 |
| 第六篇 / 19 | Framework Comparison | 已有：核心已去框架化并以端口隔离模型、沙箱、持久化 | 增加架构约束测试，阻止第三方向导依赖进入核心，并记录自研能力与框架概念映射 |
| 第六篇 / 20 | Dify / Coze | 部分：Web 工作台已有会话、Run、Trace、终端和审批，但没有声明式 Agent 配置 | 新增受控的只读 Agent Profile 与图拓扑查询 API；不实现任意低代码图编辑，避免绕过类型和安全门禁 |
| 第七篇 / 21 | CLI Agent | 部分：代码修改、真实终端、测试、修复循环和错误回传已存在 | 将 Tool Registry、知识编译、检索预算和命令审批接入 Coder→Ops；增加真实仓库 EDD 和失败自愈轨迹断言 |
| 第七篇 / 22 | GUI Agent | 部分：Playwright 线程亲和服务、截图、DOM 和 Reviewer 已存在 | 增加页面动作工具协议、证据选择器、操作级超时和视觉任务 EDD；每次结论必须引用 DOM、截图或测试证据 |
| 第八篇 / 23 | Evaluation | 已有：Benchmark、`pass^k`、TTFT、JSON 报告、真实模型 EDD 和 OTel 已存在 | 扩展为逐章节能力集、轨迹评分、成本预算、失败分类与 CI 阈值；外部 EDD 保持显式开关 |
| 第八篇 / 24 | Security | 部分：工作区成员权限、HITL、路径边界、Docker 隔离已有 | 新增 Prompt Injection 标记、工具参数策略、输出脱敏、权限违规持久化和红队任务集 |
| 第八篇 / 25 | Deployment | 部分：Dockerfile、开发/生产 Compose、健康检查、日志卷和 PostgreSQL 已存在 | 增加启动探针、就绪探针、优雅关闭、资源配额、迁移门禁、备份恢复演练文档和部署 EDD |
| 第八篇 / 26 | Inference Framework | 部分：OpenAI 兼容 `LlmClient`、模型路由、超时、熔断和 fallback 已存在 | 新增端点能力描述、并发/速率预算、流式背压指标和可移植模型服务契约；不把具体推理服务器依赖引入核心 |

## 5. 分篇实施边界

### 5.1 第二篇：Agent 的大脑

第二篇分为两个可独立回归的提交组，但作为一个里程碑统一验收。

#### 2A：Prompt、Context 与 Intent

- `agent-core/prompt`：版本化 Prompt 模板、渲染变量、静态/动态分区和指纹。
- `agent-core/context`：token 估算器、上下文条目优先级、摘要端口和压缩结果。
- `agent-core/intent`：强类型任务类型、复杂度、能力集合和路由决定。
- `PlannerNode`：消费上述端口，把 Prompt 版本、指纹、上下文裁剪信息和路由证据写入精确状态键。
- `ProductionGraphConfiguration`：以构造器注入默认实现，现有调用方通过兼容构造器保持迁移可控。

#### 2B：Memory、Runtime 与 Harness

- `agent-rag/memory`：为动态记忆引入时间衰减、访问频率和重要度，数据库迁移保持向前兼容。
- `agent-core/engine`：引入不可变执行预算、停止原因和无进展检测。
- `agent-core/harness`：定义有序 Hook 事件和失败隔离规则；Hook 失败必须进入审计但不得静默改变状态。
- `agent-web`：把预算和 Hook 配置映射到精确环境变量，并把停止原因呈现在已有 Trace 通道。

第二篇不实现 MCP、多 Agent 或低代码配置，这些能力分别留在第四、第五和第六篇。

### 5.2 第三篇：Agent 的知识

在现有 `agent-rag` 上增加可组合检索流水线和项目知识编译。向量、BM25 与符号检索继续作为稳定基线；改写、HyDE 和 rerank 通过端口注入，失败时明确降级到基线并写 Trace。

### 5.3 第四篇：Agent 的手脚

以 `ToolRegistry` 为单一工具入口，再向上适配 MCP 和 Skills、向下适配 AST、终端与 Playwright。所有工具共用 Schema、权限、审批、超时、日志和结果协议。

### 5.4 第五篇：神经系统

在自研 `StateGraph` 上增加子图和 handoff，不引入外部图框架。父运行负责预算和最终状态，子运行拥有独立 Trace 与受限上下文，合并只允许预先声明的状态字段。

### 5.5 第六篇：框架与平台

该篇主要形成架构守卫和产品化查询接口。Agent Profile 只描述允许的图、模型任务、工具集合和预算；运行时仍由 Java 类型和 Spring 构造器装配，不执行用户提交的任意类名或表达式。

### 5.6 第七篇：综合实战

把前几篇端口接入现有 Coder→Ops→Reviewer 生产链，并以真实临时仓库、真实 Docker/PTY、真实 Playwright 页面和可选真实 LLM EDD 验证端到端自愈。

### 5.7 第八篇：工程化

扩充 `agent-eval` 的轨迹、成本、安全和部署门禁；安全违规、预算耗尽和模型降级都必须进入结构化报告。Docker 与 PostgreSQL 集成测试在具备 Engine 的当前环境实际执行，外部模型测试仍由明确环境开关控制。

## 6. 跨模块数据流

一次用户请求按以下顺序处理：

1. 会话服务从 PostgreSQL 读取用户、工作区、历史完成轮次和当前轮次。
2. `ContextWindowManager` 根据模型预算、优先级和受保护消息生成上下文包。
3. `PromptCatalog` 组装静态规则、动态任务、项目知识、长期记忆和上下文摘要，并生成版本与指纹。
4. Intent Router 输出强类型 `TaskDecision`，决定直接回答、单工具动作或完整 Agent 图，并记录复杂度和能力需求。
5. `StateGraph` 在 `ExecutionBudget` 和 Hook 链约束下执行节点；模型、工具、终端和浏览器过程继续推送 Trace。
6. 每个模型和工具调用产生结构化审计证据；Checkpoint 继续保存完整不可变状态。
7. 最终回答、代码 Diff、终端日志、浏览器证据和停止原因由现有 Web 通道呈现。
8. Benchmark 与 EDD 对终态、输出、轨迹、TTFT、token、成本、安全和资源清理进行评分。

## 7. 错误处理与降级

- Prompt 缺少精确变量、版本不存在或模板重复时立即失败，不回退到任意模板。
- 上下文超限时按声明的优先级压缩；系统规则、当前用户请求和最新工具错误为受保护项。受保护项本身超过硬上限时返回明确预算错误。
- Intent 模型返回不合法结构时记录原始安全摘要并降级到无副作用问答；存在明确代码动作时不得静默降级为聊天。
- 摘要、查询改写、HyDE、rerank 等增强模型调用失败时保留异常证据，并降级到最后一个可证明的确定性结果。
- Hook 不能吞掉节点异常。非关键观测 Hook 失败写审计后继续；权限、审批和预算 Hook 失败则拒绝执行。
- 工具和 MCP 参数未经 Schema 与权限校验不得执行；路径、命令和 URL 继续使用精确类型验证。
- 任何超时、预算耗尽、无进展停止和资源清理失败都写入状态、Checkpoint、日志、Trace 与评测报告。

## 8. 测试与验收策略

每篇均执行四层门禁：

1. 领域单元测试：验证不可变 record、精确字段、排序、预算和错误协议。
2. 模块集成测试：验证 Planner、StateGraph、RAG、工具注册、数据库迁移和 Web 事件连接。
3. 真实基础设施测试：Docker/PTY、PostgreSQL/pgvector 和 Playwright 在可用环境实际运行；缺少 Engine 的其他环境通过 JUnit assumption 明确跳过。
4. EDD：使用版本化任务集验证自然语言质量、连续对话、工具选择、失败恢复、TTFT、token、轨迹和安全规则。外部 LLM 只在 `AGENT_LLM_ENABLED=true` 时实际调用。

第二篇完成的最低验收包括：

- 相同静态 Prompt 和相同变量产生稳定指纹；缺少变量明确失败。
- 长对话能在 token 预算内保留系统规则、当前请求和最新工具错误，并报告裁剪与摘要信息。
- 简单问答、复杂问答、代码修改、命令执行和复合任务得到精确强类型决策。
- 图在步数、总时长、空闲时长、token 或重复进展任一预算耗尽时确定性停止并持久化原因。
- 动态记忆排序体现重要度、访问频率和时间衰减，架构规则不随时间衰减。
- 现有连续对话、问答快路径、Coder→Ops→Reviewer、HITL、RAG、日志和 EDD 回归不退化。

## 9. 提交与文档维护

- 总设计、每篇规格、每篇计划和每个生产里程碑分别提交。
- Conventional Commit 的 scope 必须精确指向 `architecture`、`prompt`、`context`、`intent`、`runtime`、`memory`、`rag`、`tool`、`security`、`eval` 等实际改动域。
- 每篇结束时更新 `README.md` 的功能与配置、`docs/ENGINEERING_PITFALLS.md` 的问题现象/根因/解决方案，以及对应 EDD 任务集。
- `.env`、`logs/`、`target/`、模型真实输出和 API Key 不得进入提交。
