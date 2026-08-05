# Production Agent 闭环设计

## 目标

将 Web 工作台的默认执行入口从固定 `demo-agent` 改为真实 `code-agent`。用户提交自然语言任务后，系统在指定 Git 工作区内调用模型生成计划和 Unified Diff，应用变更，执行测试命令，收集可选网页证据并完成质量审查。每个节点的模型请求、模型响应、工具输入、工具输出和错误堆栈都写入不可变 `AgentState`，因此可以通过现有 REST 快照、历史和 Trace WebSocket 审计完整调用链。

## 方案选择

1. **仅改演示图**：继续在 `SampleGraphConfiguration` 中读取任务并拼接字符串。实现快，但没有真实模型、Diff、沙箱和审查能力，排除。
2. **新增独立生产图（推荐）**：保留演示图作为明确的离线示例，新增 `code-agent` GraphFactory，通过构造器注入真实节点依赖。模型未配置时生产图明确返回配置错误，不伪造成功结果。
3. **重写 AgentRunService 和 WebSocket 协议**：可一次性定义更丰富事件，但会破坏已经验证的 Checkpoint/HITL 生命周期，范围和回归风险过大，排除。

采用方案 2。已有 Core、Sandbox、Harness 和前端协议继续复用；新增能力集中在生产 Graph wiring、代码生成协议、默认配置和证据展示。

## 生产图与状态协议

图标识固定为 `code-agent`，节点顺序为：

`planner -> coder -> ops -> reviewer -> END`

Reviewer 返回 `reviewer.approved=false` 且未超过最大修复次数时，路由回 `coder`；超过次数后结束并保留失败反馈。图最大步数为 12。

初始状态由 Web 请求提供以下变量：

- `planner.task`：用户自然语言任务。
- `planner.repositoryId`：记忆和审计范围标识。
- `planner.userId`：记忆和审计范围标识。
- `coder.workspacePath`：现有 Git 工作树绝对路径。
- `reviewer.url`：可选的 HTTP/HTTPS 页面地址；为空时 Reviewer 仅基于代码变更和 Ops 证据审查。

新增证据键使用精确字符串，不依赖模糊推断：

- Planner：`planner.request`, `planner.response`, `planner.model`, `planner.error`。
- Coder：`coder.request`, `coder.response`, `coder.unifiedDiff`, `coder.updatedFiles`, `coder.command`, `coder.error`。
- Ops：现有 `ops.command`, `ops.exitCode`, `ops.stdout`, `ops.stderr`, `ops.timedOut`, `ops.error`, `ops.logError`。
- Reviewer：`reviewer.request`, `reviewer.response`, `reviewer.approved`, `reviewer.summary`, `reviewer.feedback`, `reviewer.model`, `reviewer.dom`, `reviewer.screenshotDataUrl`, `reviewer.error`。

## 模型输出协议

Planner 保持现有纯文本计划协议。Coder 使用 CODE 路由并要求返回一个 JSON 对象，字段固定为：

```json
{"summary":"...","unifiedDiff":"diff --git ...","command":"mvn test"}
```

`unifiedDiff` 必须是非空 Unified Diff，`command` 必须是非空 Bash 命令。对象未知字段、缺失字段、非字符串字段和非法 Diff 均视为节点失败，完整异常堆栈写入 `coder.error`，不得应用部分结果。模型上下文包含用户任务、Planner 计划、工作区受限快照、上一轮 Ops 结果和 Reviewer 反馈。

Reviewer 在 `reviewer.url` 非空时使用 Playwright 获取最终 URL、DOM 和截图；为空时跳过浏览器工具。Reviewer 使用 VISION 路由返回现有三字段 JSON 协议。浏览器或模型失败同样写入 `reviewer.error`。

## 工作区安全边界

生产图只接受现有目录，并要求 `AstService.applyDiff` 的 Git 工作树校验通过。快照服务只读取工作树内的受限文件集合，跳过 `.git`、`target`、`node_modules`、构建缓存和二进制文件，并限制文件数量和总字节数。Diff 仍由 JGit 应用，并保留路径越界和冲突错误。

快照还必须精确排除 `.env`、以 `.pem` 结尾和以 `.key` 结尾的文件，防止本地模型密钥、私钥和证书材料进入 Coder 请求。该排除在读取文件内容之前执行，不依赖文件是否被 Git 跟踪。

## Compose 中的 Docker 工作区

宿主机直接启动 `agent-web` 时，`DockerTarget` 继续把本机工作区直接绑定到一次性沙箱。Compose 启动时，应用看到的是 `/agent-workspace`，而 Docker-Java 连接的是宿主 Docker Engine，二者不能共用同一个 bind source 字符串。

Compose 因此向 `DockerTarget` 提供精确的工作区源容器名。Docker 后端 inspect 该容器，只接受 destination 与 `/agent-workspace` 完全相等、可读写、source 非空且没有 named-volume 名称的唯一 mount；随后仅把该 source 绑定到一次性沙箱的 `/workspace`。找不到、重复、只读或 named-volume mount 都必须在创建沙箱前失败。禁止使用 `volumes-from`，避免把 `/var/run/docker.sock` 和 Web 容器的其他挂载暴露给执行命令。

两套 Compose 文件都将项目根目录读写绑定到 `/agent-workspace`，并分别传入精确容器名 `agent4j-web-local` 和 `agent4j-web`。宿主直跑路径使用无源容器的 `DockerTarget`，保持 Phase 2 行为。

## Spring 装配

- `ModelGatewayConfiguration` 提供已启用的 `ModelRouter`；新增生产 Graph 配置使用构造器注入，不在 Core 读取 Spring 环境。
- 新增无 RAG 时的空 `MemoryContextProvider`，使记忆是可选能力而非启动前置条件。
- 新增 `SandboxTerminalService` 和 `PlaywrightBrowserService` Bean，并在 Spring 生命周期结束时关闭。
- 应用默认关闭 `agent.production.enabled`；两套 Compose 文件将其默认值设为 `true`。只有 `agent.production.enabled=true` 且 `agent.llm.enabled=true` 创建了 `ModelRouter` 时才注册 `code-agent`，模型端点或模型名缺失时启动明确失败，绝不生成演示结果。
- `.env.example` 提供生产图所需的 `AGENT_CODE_WORKSPACE`, `AGENT_CODE_REPOSITORY_ID`, `AGENT_CODE_USER_ID`, `AGENT_CODE_REVIEWER_URL` 和模型配置。

## Web 交互

任务输入默认提交 `code-agent`，高级设置允许显式修改 Graph ID 和完整初始状态。工作台的节点详情读取最新 Run 状态和历史，按节点展示任务、模型、请求、响应、Diff、命令、日志、审查结论和错误。`demo-agent` 只作为带有 Demo 标识的离线图保留。

## 错误与审计

任何模型、AST、沙箱、Playwright 或 JSON 解析异常都写入对应节点错误键并继续生成可审计的状态；只有图结构或 Checkpoint 级故障才由 `AgentRunService` 写入 Run 失败状态。状态变量不删除、不覆盖为成功文本，修复循环通过显式 `reviewer.feedback` 和 `ops` 证据驱动。

## 测试门禁

先添加失败测试，再实现：

1. Coder JSON 协议、工作区快照和真实 Git Diff 应用。
2. 生产 Graph wiring、修复路由和无模型配置错误。
3. Web 创建 `code-agent` Run 的请求与默认状态。
4. 前端默认 graph、节点证据渲染和错误展示。
5. Java 21 Maven 全量测试、前端 Vitest/build、Docker Compose 启动和真实浏览器回归。
6. 敏感文件快照排除，以及 Compose 容器 mount source 解析、只读/重复/缺失边界。
