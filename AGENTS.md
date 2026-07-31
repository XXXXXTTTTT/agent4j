项目定位：基于 Java 21 与 Spring Boot 3.3+，无依赖第三方向导库（如 LangChain4j/LangGraph4j），自研图状态机引擎（Graph State Engine），融合 AST 代码解析（B类）与 CLI/GUI 自动化（C类）的企业级全自动 Agent 运行平台。

1. 项目核心目标与设计原则
去框架化自研（No Framework Lock-in）：手写核心状态机调度内核（Graph Engine），深入掌握 Agent 底层 Loop、State、Checkpoint 与中断机制。

极致并发性能（Java 21 Virtual Threads）：全面采用 JDK 21 虚拟线程处理大模型流式 I/O、沙箱命令行阻塞与 Web 自动化等待。

B+C 体系融合：

B类（代码与研发）：基于 AST 静态解析、Diff/Patch 增量修改、JGit 仓库控制。

C类（操作与自动化）：基于 Docker/PTY 伪终端交互、Playwright 无头浏览器视觉自动化。

企业级工程 Harness：具备分布式状态落盘、人机协同（Human-in-the-Loop）审批中断、全链路 Trace 可观测性与模型智能降级。

2. 技术栈选型 (Technology Stack)
模块	技术选型	版本/组件	选型理由
基础语言与框架	Java 21 + Spring Boot	3.3.x	利用虚拟线程（Loom）、Record、模式匹配与响应式生态
通信与长连接	WebFlux + WebSocket / SSE	-	高并发处理大模型流式输出与终端日志推送
代码与 AST 解析	JavaParser + java-tree-sitter + JGit	最新稳定版	毫秒级提取代码结构，精准局部 Patch，免全量覆盖
CLI & 沙箱	Docker-Java + pty4j	-	容器物理隔离 + JetBrains 伪终端（ANSI 颜色/交互捕获）
GUI 浏览器自动化	Playwright for Java	微软官方库	原生 Java 控制 Chromium，提取 DOM 与视觉截图
模型网关与协议	One-API / Spring AI MCP Starter	0.1.x +	统一 OpenAI 协议格式，支持动态路由与 MCP 工具扩展
状态持久化	PostgreSQL / Redis + Jackson	-	状态 Checkpoint 序列化落盘与断点续传
3. 系统架构与分层设计
                                [ 用户前端 / Web UI / 外部 API ]
                                               │
                                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                            网关与鉴权层 (Gateway & Auth Service)                            │
└──────────────────────────────────────────────┬──────────────────────────────────────────────┘
                                               │ (WebSocket / REST)
                                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                          Agent 核心调度引擎 (Agent Core Service)                            │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │ 自研图状态机内核 (Graph Engine)                                                       │  │
│  │                                                                                       │  │
│  │  PlannerNode (规划) ──> CoderNode (编写) ──> OpsNode (CLI执行) ──> ReviewerNode (审查) │  │
│  │         ▲                                         │                      │            │  │
│  │         └────────────────── 修复循环 ─────────────┴──────────────────────┘            │  │
│  └───────────────────────────────────────────┬───────────────────────────────────────────┘  │
│                                              │                                              │
│  ┌───────────────────────────────────────────┴───────────────────────────────────────────┐  │
│  │ 状态与生命周期管理器 (State & Checkpoint Manager)                                     │  │
│  │  - AgentState (Record 不可变状态)                                                     │  │
│  │  - Checkpointer (数据库持久化 / 挂起与恢复 / HITL 人工审批)                             │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬───────────────────────────────┬──────────────────────────────┘
                               │                               │
                               ▼                               ▼
┌─────────────────────────────────────────────┐ ┌─────────────────────────────────────────────┐
│        能力网关 (MCP / Tool Registry)        │ │        执行沙箱集群 (Sandbox Cluster)       │
│ - AST 代码解析工具 (JavaParser)             │ │ - Docker 容器沙箱                           │
│ - Git 操作工具 (JGit)                       │ │ - PTY 伪终端交互 (pty4j)                    │
│ - Playwright 视觉自动化 (Playwright-Java)   │ │ - Playwright Chromium 实例                  │
└──────────────────────────────┬──────────────┘ └─────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                          模型中转与智能路由器 (Model Gateway Router)                         │
│ - 根据任务类型 (视觉/写代码/简单分类) 动态分发至 Claude 3.5 / DeepSeek-R1 / Ollama          │
│ - 多级降级熔断机制 (Fallback Chain)                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
4. 项目目录结构 (Directory Structure)
Plaintext
agent-runtime-system/
├── agent-core/                      # Agent 核心引擎模块
│   ├── src/main/java/com/agent/core/
│   │   ├── engine/                  # 图状态机内核
│   │   │   ├── AgentState.java      # 全局不可变状态 (Record)
│   │   │   ├── Node.java            # 节点抽象接口
│   │   │   ├── Condition.java       # 条件路由接口
│   │   │   ├── StateGraph.java      # 图引擎，基于 Virtual Threads 驱动
│   │   │   └── Checkpointer.java   # 状态持久化与断点续传接口
│   │   ├── llm/                     # 模型交互与路由层
│   │   │   ├── ChatMessage.java     # 消息实体 (SYSTEM, USER, ASSISTANT, TOOL)
│   │   │   ├── LlmClient.java       # 基于 RestClient / SSE 的轻量客户端
│   │   │   └── ModelRouter.java     # 智能模型分发与熔断降级
│   │   ├── tool/                    # MCP & 工具注册中心
│   │   │   ├── ToolRegistry.java    # 工具动态注册与按角色掩码
│   │   │   └── annotation/          # @AgentTool 自定义注解
│   │   └── nodes/                   # 预置业务 Agent 节点实现
│   │       ├── PlannerNode.java     # 任务拆解节点
│   │       ├── CoderNode.java       # 代码修改节点
│   │       ├── OpsNode.java         # CLI 运行测试节点
│   │       └── ReviewerNode.java    # 视觉/逻辑审查节点
├── agent-sandbox/                   # 沙箱与环境交互模块
│   ├── src/main/java/com/agent/sandbox/
│   │   ├── docker/                  # Docker-Java 隔离容器管理
│   │   ├── pty/                     # pty4j 伪终端控制
│   │   ├── ast/                     # JavaParser / Tree-sitter 代码解析
│   │   └── browser/                 # Playwright for Java 无头浏览器集成
├── agent-web/                       # API 网关与 Web 服务入口
│   ├── src/main/java/com/agent/web/
│   │   ├── controller/              # REST & WebSocket API
│   │   └── config/                  # Spring Config (虚拟线程开启、Async 配置)
└── pom.xml                          # 根 Maven 依赖配置
5. 编码规范与 Java 21 指引
不可变状态模式：所有在节点间传递的 AgentState 必须使用 Java record 实现。状态变更必须返回新的 record 实例，严禁直接修改原状态。

虚拟线程优先：所有图循环调度、网络请求与 CLI 执行，必须显式运行于虚拟线程调度器：

Java
Executors.newVirtualThreadPerTaskExecutor()
强类型异常与兜底：禁止忽略异常。大模型 API 异常必须捕获并触发 ModelRouter 降级，工具执行失败必须把错误栈（Traceback）封包返回给 Agent 修复。

简洁注释：方法与核心算法需编写清晰的 Javadoc 简述。

git
使用git做版本管理，在闭环/里程碑时进行git提交, 并必须维护好.gitignore
同时git提交规范为

6. 分阶段实施路线图 (Implementation Roadmap)
阶段一：图引擎内核与 LLM 客户端 (Phase 1)
[ ] 配置 Maven 基础依赖（Spring Boot 3.3+, Java 21）。

[ ] 手写 AgentState、Node、Condition 与 StateGraph。

[ ] 编写基于虚拟线程的 StateGraph.execute() 循环调度器，支持最大步数熔断。

[ ] 实现 LlmClient，对接 OpenAI 标准格式 API，支持 SSE 流式与 Function Calling。

[ ] 编写 JUnit 5 单元测试：跑通最简 ReAct 循环（Planner -> Tool -> End）。

阶段二：B类 Code 工具与沙箱环境 (Phase 2)
[ ] 集成 JavaParser，编写 AstService 支持方法/类提取与 Diff 应用。

[ ] 集成 Docker-Java 与 pty4j，实现 SandboxTerminalService，支持 Bash 命令异步执行与日志捕获。

[ ] 实现 CoderNode 与 OpsNode，使 Agent 能够修改代码并运行测试。

阶段三：C类 GUI 自动化与模型路由网关 (Phase 3)
[ ] 集成 Playwright for Java，实现网页导航、点击、DOM 提取与截图。

[ ] 编写 ModelRouter，根据 TaskType（写代码/视觉/快速分类）智能匹配模型，并实现 Resilience4j 降级熔断。

[ ] 实现 ReviewerNode，结合 Playwright 截图与测试日志进行最终质量判断。

阶段四：Harness 工程化与分布式落盘 (Phase 4)
[ ] 实现 Checkpointer，将 AgentState 序列化写入数据库。

[ ] 实现 Interrupt 挂起机制与 REST 控制器，支持 HITL 人工审批危险操作。

[ ] 建立基于 WebSocket 的实时日志与思考链路推送（Trace）。

7. Git 提交与工程隔离规范 (Git Commit & Exclusion Rules)

为了保障代码仓库的干净、可追溯以及自动化 CI/CD 流程的稳定性，所有开发人员与 AI Coding Agent 必须严格遵守以下版本控制规范。

    7.1 约定式提交规范 (Conventional Commits)

    每次执行 `git commit` 时，提交信息必须严格遵循 Conventional Commits 格式：

    ```text
    <type>(<scope>): <description>

    [optional body]

    [optional footer(s)]
    1. 核心 Type 类型定义Type用途  PDF对应 SemVer 版本升级  PDFfeat新增功能  MINOR (次版本升级)  fix修复 Bug  PATCH (补丁版本升级)  BREAKING CHANGE破坏性变更（API 不兼容）  MAJOR (主版本升级)  docs文档更新（如 README、注释等）  不影响版本  style代码格式调整（空格、缩进，不改变逻辑）  不影响版本  refactor代码重构（非功能新增也非 Bug 修复）  不影响版本  test添加或修改测试用例  不影响版本  chore构建系统或辅助工具的变更  不影响版本  build / perf构建过程、持续集成或性能优化  不影响版本  2. 各字段填充规则Scope（团队要求必填）：指定受影响的模块或功能区域，必须放在圆括号中。例如：feat(auth):、fix(payment):。  Description（必填）：简短描述变更目的，通常不超过 72 个字符，使用祈使句描述。  Body（可选）：解释“为什么”做此改动，解释原因与设计意图，每行字数建议不超过 72 字。  Footer（可选）：包含标记词与字符串。若有破坏性变更，必须在 Type/Scope 后加 ! 标记，或在 Footer 中用大写的 BREAKING CHANGE: 标注。  3. 标准规范示例（参考）  基础新增：feat(auth): 新增 JWT token 自动刷新机制  Bug 修复：fix(payment): 修复订单金额计算精度丢失的问题  带说明正文：  Plaintextfix(cache): 修复缓存键冲突导致数据错乱的问题

    不同用户在同一时间请求相同资源时，由于缓存键仅用了资源 ID 而未包含用户 ID，
    导致数据互相覆盖。现改为 "${userId}:${resourceId}" 格式的复合键。
    破坏性变更[cite: 1]：Plaintextfeat(api)!: 用户接口返回结构调整为嵌套格式

    BREAKING CHANGE: /api/user 返回的 data 字段由扁平结构改为嵌套结构，旧版客户端需同步更新解析逻辑。
    7.2 环境隔离与 .gitignore 防护策略为避免将临时构建产物、IDE 配置或敏感密钥误提交至 Git 仓库，Agent 必须自动维护根目录的 .gitignore 文件。1. 严格禁止提交的文件（Must Exclude）Java 构建产物：target/、*.class、*.jar、*.warIDE & 编辑器配置：.idea/、*.iml、.vscode/、.settings/、.classpath、.project操作系统垃圾：.DS_Store、Thumbs.db本地运行日志与临时文件：*.log、logs/、tmp/、*.out敏感信息与环境变量：.env、*.pem、*.key、含有真实 API Key/Secret 的配置文件2. 提交前强制校验流程 (Pre-commit Checklist)自动更新 .gitignore：在创建新模块（如新建 agent-sandbox）或引入新工具时，确保其产生的日志和临时文件路径已写入 .gitignore。检查 Git 暂存状态：在执行 git commit 前，必须先运行 git status 确认被修改的文件列表。若发现非核心代码文件（如 IDE 配置文件），立即终止提交并加入 .gitignore。提交原子性：单次 Commit 仅包含与该 Task 相关的改动，严禁混合多个不同功能的修改。

8. 给 AI Coding Agent (Codex/Claude Code) 的执行指令
当你向 AI Coding Agent 发送任务指令时，请遵循以下规则：

严格遵循本文档：所有新增类与模块必须符合本文档定义的包路径与架构规则。

拒绝第三方 Agent 库：严禁引入 langchain4j 或 langgraph4j。

分阶段提供完整代码：代码必须完整无省略，包含必要的抽象类和单元测试。

单次聚焦单一 Milestone：每次请求仅让 AI 实现路线图中的某一个阶段或具体类，切勿一次性生成过多无校验的代码。