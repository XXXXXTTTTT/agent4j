# Agent4J 官方 MCP、外部 Skill 与受治理 CLI 工作台设计

## 1. 目标与范围

本期交付三个可审计入口：

1. 官方 MCP reference server 清单发现、预览、确认安装、Docker 隔离 stdio 运行和按安装实例注册工具。
2. GitHub-only 外部 Skill 搜索、内容审查、工作区默认安装；用户明确选择后才允许用户全局安装。
3. 聊天输入 `/` 时选择 `CliCommandCatalog` 中的受治理命令，以结构化参数创建专用 CLI Run，并复用现有审批、终端日志和 Trace。

HTTP/HTTPS MCP 配置继续由现有 `McpGatewayProperties`、`McpRuntime` 提供。任何新安装都不得把未确认的远程内容、任意 shell 文本或 Skill 自带工具权限直接送入执行链。

本文件中的新类、表和 DTO 是本期新增契约；现有标识符只引用已读取源码中的精确名称。

## 2. 现有边界（不可改变）

- `McpTransport` 只有 `request(McpJsonRpcRequest)`、`notify(McpJsonRpcRequest)`、`close()`；stdio 适配器必须实现该接口。
- `McpClient` 当前固定调用 `initialize`、`tools/list`、`tools/call`，并复用 `McpToolRegistryAdapter` 注册到 `ToolRegistry`。
- `ToolRegistry` 当前没有撤销接口；本期增加按 `installationId` 原子注册/撤销端口，保留现有 `register/registerAll/find/list/execute` 行为。
- `CliCommandCatalog` 的真实字段为 `name`、`executable`、`fixedArguments`、`riskLevel`、`requiredCapabilities`，其 `authorize(CliCommandIntent, CliAuthorizationContext)` 是唯一授权入口。
- `AgentRunService.start` 只能启动 `GraphRegistry` 中已注册的精确 graphId。现有生产图精确为 `code-agent`，因此 CLI 必须增加独立 graphId `governed-cli`，不得把 CLI 请求伪装成普通对话。
- `ConversationComposer.tsx` 当前只有 textarea、模型组 select 和普通会话提交；新增命令面板必须在 `Workbench.tsx` 的真实挂载路径中显示。

## 3. 官方 MCP 清单

发现源固定为：

- Contents API 根：`https://api.github.com/repos/modelcontextprotocol/servers/contents`
- 固定提交后的 Contents/Raw 地址：将 `main` 替换为已解析的 commit SHA。

客户端先读取根 Contents 响应，再读取 `src/` Contents 响应。当前 README 列出的目录为 `everything`、`fetch`、`filesystem`、`git`、`memory`、`sequentialthinking`、`time`；实现必须按固定 commit 的实际 `src/` 响应枚举，不得硬编码为成功条件。

每个条目保存：`serverKey`、`repositoryPath`、`commitSha`、`blobSha`、`sourceUrl`、`rawUrls`、`displayName`、`description`、`version`、`license`、`language`、`launchSpec`、`requiredEnvironmentNames`、`riskLevel`、`requiredCapabilities`、`contentSha256`。未知字段拒绝进入结构化记录；单项解析失败记录错误并不污染上一次可用缓存。

解析规则：

- TypeScript：只接受实际 `package.json` 的 `name`、`version`、`description`、`license`、`bin`；启动必须来自已验证的固定包命令模板，不能执行 README 任意代码块。
- Python：只接受 `pyproject.toml` 的 `[project] name/version/description/license` 和 `[project.scripts]`；README 中 `uvx`、`python -m` 仅作为展示证据，不能覆盖结构化入口。
- 包管理器、包名、版本、入口和参数组成不可变 `LaunchSpec`；下载时固定版本并保存 SHA-256，运行时禁止隐式升级或联网安装。

目录客户端使用虚拟线程、连接/读取超时、最大响应字节数、ETag/If-None-Match、TTL 和 GitHub 失败审计。命中限流时返回未过期缓存；无缓存时返回明确的 `CATALOG_UNAVAILABLE`，不能伪造目录。

## 4. MCP 安装与 Docker 运行

安装范围枚举为 `WORKSPACE`、`USER_GLOBAL`。默认 `WORKSPACE`，记录 `workspaceId` 与 `actor.userId`；`USER_GLOBAL` 只能在请求中显式携带 `scope=USER_GLOBAL`，并记录 `workspaceId=null`。全局安装仅在用户自己的工作区请求中可见，启动时必须提供目标工作区并重新通过 `WorkspaceAccessService.requireWorkspace`。

安装状态枚举为 `PREVIEW`、`PENDING_APPROVAL`、`INSTALLING`、`RUNNING`、`FAILED`、`STOPPING`、`STOPPED`、`REJECTED`。预览不写安装表、不下载、不启动进程。

确认后执行顺序固定为：校验固定 commit/blob SHA -> 下载到工作区 `.agent4j/mcp/<installationId>/staging` -> 校验 SHA-256 -> 原子改名为 `active` -> 由 Docker 运行器启动 stdio。禁止使用本机 `ProcessBuilder`。

Docker 运行器必须定义：镜像、容器工作目录、工作区挂载模式、无网络默认策略、CPU/内存/进程数/输出上限、环境变量白名单。密钥值来自运行时 SecretProvider，只保存环境变量名称；命令和参数只能来自已确认的 `LaunchSpec`。

新增 `McpInstallationToolBinding` 记录每个本地工具名与 `installationId`。安装启动成功后，MCP client 完成握手和分页发现，再原子注册绑定；任一工具失败则整批回滚。停止/卸载先阻止新调用，等待在途调用结束，原子撤销绑定，再关闭 Docker 容器和 client。应用启动时按状态恢复 `RUNNING/INSTALLING` 记录，重复恢复必须幂等；退出和协议错误统一转为 `FAILED` 并审计。

## 5. GitHub 外部 Skill

只允许 GitHub 仓库来源。搜索结果不直接启用：先取得仓库默认分支的精确 commit SHA，再读取路径为 `SKILL.md` 的文件及其 blob SHA。单文件大小、UTF-8、路径和 SHA-256 必须校验；不下载可执行脚本或未声明的附加文件。

Skill 记录字段：`skillId`、`repositoryUrl`、`repository`、`commitSha`、`blobSha`、`path`、`license`、`contentSha256`、`summary`、`requestedToolNames`、`scope`、`workspaceId`、`actorUserId`、`status`。默认 `WORKSPACE`；用户显式 `USER_GLOBAL` 才允许全局安装。

安装预览展示来源、commit/blob SHA、许可证、摘要、声明工具和风险。确认后保存不可变快照。Skill 只能引用本地已注册且通过 `ToolRegistry` 能力/风险策略的工具；禁止 Skill 创建工具、执行 shell、覆盖系统 prompt 或访问安装目录之外的文件。提示词注入、未知 front matter、未知工具名均拒绝安装。

## 6. CLI 工作台与专用 Run

现有 `CliCommandDefinition` 的精确字段为：`name`、`executable`、`fixedArguments`、`riskLevel`、`requiredCapabilities`。本期不扩展该核心 record，也不引入未经现有源码验证的描述、命名参数或参数类型字段。命令目录 API 返回上述五个字段，并增加由服务根据 `CliCommandIntent` 上限返回的 `maxArguments=64`。前端按 `riskLevel` 展示审批状态：`READ_ONLY` 为自动允许，`MUTATING` 为等待用户批准；`DESTRUCTIVE` 不出现在首期目录。`fixedArguments` 是不可变的字符串 token 列表，按定义顺序渲染在 executable 之后。

CLI Run 请求字段精确为：`commandName`、`arguments`、`timeoutSeconds`。其中 `arguments` 是与 `CliCommandIntent.arguments` 相同的有序 `List<String>`；每个元素都是一个完整 token，不允许 null、空 token、控制字符或 Shell 控制字符（`;`、`&`、`|`、`<`、`>`、反引号、`$`），最多 64 个元素，服务不得把它解释为 Shell 片段。请求拒绝 `approval`、`shell`、`bashCommand`、未声明字段和工作区外路径。服务使用当前 `WorkspaceAccessService` 返回的 `workspacePath` 和 `WorkspaceTerminalTargetResolver` 构造 `CliCommandIntent`，并把唯一授权入口交给 `CliCommandCatalog.authorize`。

专用 graphId 精确为 `governed-cli`，图只注册现有 `ops` 节点：入口为 `ops`，执行完成后到 `StateGraph.END`。创建 Run 前写入以下已存在的状态变量：`OpsNode.COMMAND_NAME_KEY` (`ops.commandName`)、`OpsNode.COMMAND_ARGUMENTS_KEY` (`ops.commandArguments`，JSON 字符串数组)、`CoderNode.WORKSPACE_PATH_KEY` (`coder.workspacePath`) 和 `PlannerNode.REQUIRED_CAPABILITIES_KEY` (`planner.requiredCapabilities`，由命令定义的 `requiredCapabilities` 按 `RequiredCapability` 枚举声明顺序连接为逗号分隔名称)。进入 `ops` 前由 `CliApprovalInterruptPolicy.evaluate` 调用目录授权：`READ_ONLY` 直接运行；`MUTATING` 产生 `RunStatus.WAITING_APPROVAL`；目录不得注册 `DESTRUCTIVE` 命令。中断详情使用现有 `InterruptRequest` 字段，至少包含 `commandName`、`commandArguments`、渲染后的 `command`、`riskLevel`、`commandSha256` 和 `authorizationReason`。批准/拒绝只能调用现有 `POST /api/runs/{runId}/approval`，提交 `ApprovalRequest { decision, expectedVersion, reason, variableUpdates }` 并由 `AgentRunService.decide` 处理；本期 `variableUpdates` 必须为空。批准恢复 `ops`，拒绝得到 `RunStatus.REJECTED`。日志继续由 `RunTerminalController` 的 `/api/runs/{runId}/logs` 和 Trace `/api/runs/{runId}/events` 提供。

## 7. 精确管理 API

- `GET /api/mcp/catalog` -> `CatalogView { repository, commitSha, fetchedAt, expiresAt, etag, status, servers, errors }`
- `POST /api/mcp/catalog/refresh` -> `CatalogRefreshView { status, commitSha, fetchedAt, expiresAt }`
- `GET /api/workspaces/{workspaceId}/mcp/installations` -> `List<InstallationView>`
- `POST /api/workspaces/{workspaceId}/mcp/installations/preview` 请求 `PreviewRequest { serverKey, scope, targetWorkspaceId }`，返回 `InstallationPreview { previewId, source, launchSpec, environmentNames, riskLevel, requiredCapabilities, summary, requiresConfirmation, sideEffectFree }`
- `POST /api/workspaces/{workspaceId}/mcp/installations` 请求 `ConfirmInstallationRequest { previewId, confirmationToken, scope, targetWorkspaceId }`，返回 `InstallationView`
- `DELETE /api/workspaces/{workspaceId}/mcp/installations/{installationId}` -> `InstallationView`
- `GET /api/workspaces/{workspaceId}/skills`、`GET /api/skills/search?q=...`、`POST /api/workspaces/{workspaceId}/skills/preview`、`POST /api/workspaces/{workspaceId}/skills`、`DELETE /api/workspaces/{workspaceId}/skills/{skillId}`，字段严格对应第 5 节。
- `GET /api/workspaces/{workspaceId}/cli/commands`、`POST /api/workspaces/{workspaceId}/cli/runs`，字段严格对应第 6 节。

管理审计使用新增 `CapabilityManagementAuditEvent`/`CapabilityManagementAuditSink`，独立于会话审计；事件包含 `eventType`、`actorUserId`、`workspaceId`、`installationId/skillId`、`runId`、`sourceCommitSha`、`result`、`occurredAt`，严禁写入密钥值。数据库继续使用 UTC `timestamptz`，展示层转换 `Asia/Shanghai`。

## 8. 前端与验收

`McpCatalogPanel` 必须由 `Workbench.tsx` 挂载。`ConversationComposer.tsx` 输入 `/` 或 `/` 后缀请求当前 workspace 命令目录，键盘选择、参数校验、风险/审批预览后提交结构化 CLI Run；普通文本路径保持不变。MCP/Skill 详情使用 Markdown 渲染器、代码块、表格和工具列表，不把原始 JSON/Markdown 作为唯一界面。

测试必须覆盖 DTO 精确字段、工作区权限、预览无副作用、固定 SHA/ETag/TTL/限流、Docker 启停与恢复、stdio 并发帧/通知/退出、按安装撤销、Skill 供应链拒绝、CLI 审批和前端挂载。

真实 EDD 使用仓库已有入口：

```powershell
pwsh .agent4j/acceptance/run-real-agent.ps1
pwsh .agent4j/acceptance/run-conversation-continuity.ps1
mvn -pl agent-eval -am -Dgroups=edd -Dtest=LlmEddTest test
```

只有报告中存在 `modelCallAttempts > 0`、真实 HTTP 记录、Run/Trace/Audit 证据且所有场景通过，才可称为真实 EDD；缺少 API 配置只能标记跳过，不能冒充通过。
