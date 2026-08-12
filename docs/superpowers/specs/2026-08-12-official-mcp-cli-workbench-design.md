# Agent4J 官方 MCP、外部 Skill 与受治理 CLI 工作台设计

## 1. 目标与范围

本期交付三个可审计入口：

1. 官方 MCP reference server 清单发现、预览、确认安装、Docker 隔离 stdio 运行和按安装实例注册工具。
2. GitHub-only 外部 Skill 搜索、内容审查、工作区默认安装；用户明确选择后才允许用户全局安装。
3. 聊天输入 `/` 时选择 `CliCommandCatalog` 中的受治理命令，以结构化参数创建专用 CLI Run，并复用现有审批、终端日志和 Trace。

HTTP/HTTPS MCP 配置继续由现有 `McpGatewayProperties`、`McpRuntime` 提供。任何新安装都不得把未确认的远程内容、任意 shell 文本或 Skill 自带工具权限直接送入执行链。

本文件中的新类、表和 DTO 是本期新增契约；现有标识符只引用已读取源码中的精确名称。

## 2. 已交付基线与本里程碑边界

截至 `master` 的现有实现已经交付官方目录读取、GitHub Skill 搜索、预览/确认安装、V7 快照表、管理 API、能力管理面板、`McpStdioTransport` 与受治理 CLI。下一里程碑不重复实现这些入口，只完成“确认安装后真的进入 Agent 运行时”的闭环。

- `McpTransport` 只有 `request(McpJsonRpcRequest)`、`notify(McpJsonRpcRequest)`、`close()`；stdio 适配器必须实现该接口。
- `McpClient` 当前固定调用 `initialize`、`tools/list`、`tools/call`，并复用 `McpToolRegistryAdapter` 注册到 `ToolRegistry`。
- `ToolRegistry` 当前没有撤销接口；本期增加按 `ownerId` 原子注册、停用和撤销端口，保留现有 `register/registerAll/find/list/execute` 行为。`ownerId` 对 MCP 精确等于 `installationId.toString()`，内置工具使用保留 owner `builtin`。
- `CliCommandCatalog` 的真实字段为 `name`、`executable`、`fixedArguments`、`riskLevel`、`requiredCapabilities`，其 `authorize(CliCommandIntent, CliAuthorizationContext)` 是唯一授权入口。
- `AgentRunService.start` 只能启动 `GraphRegistry` 中已注册的精确 graphId。现有生产图精确为 `code-agent`，因此 CLI 必须增加独立 graphId `governed-cli`，不得把 CLI 请求伪装成普通对话。
- 通用 `POST /api/runs` 当前接受调用方提供的 `graphId` 与完整 `initialState`，不能作为外部 Skill 的身份授权入口；它不得启动 `code-agent` 或 `governed-cli`。这两个图只能分别通过受信任的会话/`POST /api/runs/code-agent` 入口和 `POST /api/workspaces/{workspaceId}/cli/runs` 启动。
- `ConversationComposer.tsx` 当前只有 textarea、模型组 select 和普通会话提交；新增命令面板必须在 `Workbench.tsx` 的真实挂载路径中显示。
- `DockerCommandExecutor` 当前通过 `createContainerCmd` 执行一次性 `bash -lc`，只 follow stdout/stderr 并在 `finally` 删除容器；持续 MCP stdio 必须新增独立运行器，不得修改该类的一次性语义。
- `McpStdioTransport` 已经通过 `McpStdioProcess` 消费三个标准流；缺口是使用 docker-java create/start/attach API 实现该进程端口，而不是再实现 JSON-RPC transport。
- `SkillCatalog` 是构造时不可变快照，`ProductionGraphConfiguration.productionSkillCatalog` 只包含 `image-generation`。已安装 Skill 尚未按当前 `planner.userId` 与 `conversation.workspaceId` 进入生产图。
- `McpInstallationService.confirm` 与 `GitHubSkillInstallationService.confirm` 当前分别调用两次 repository 方法再写审计，审计 Sink 又使用独立事务；这不是一个原子事务。本里程碑以单个 repository transaction 完成快照、安装和审计事件写入。

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

当前官方目录从固定 commit 的 `package.json`/`pyproject.toml` 形成精确 `command`、`arguments` 与 `launchBin`，但 `npx -y`/`uvx` 仍会访问包仓库。为满足默认无网络和禁止隐式升级，确认事务不得直接启动：确认状态精确为 `STOPPED`；启动前先由受治理的物料准备器把固定版本及其锁定内容放入安装快照对应的只读物料目录，并记录物料 SHA-256。没有已校验物料时返回 `MATERIAL_NOT_PREPARED`，不得临时开放网络运行 `npx -y` 或 `uvx`。

持续运行模型新增 `McpDockerLaunchSpec`，字段精确为：`installationId`、`snapshotId`、`image`、`command`、`arguments`、`containerWorkingDirectory`、`workspaceMountMode`、`networkMode`、`memoryBytes`、`nanoCpus`、`pidsLimit`、`maxStdoutFrameBytes`、`maxStdoutBufferedBytes`、`maxStderrBytes`、`environmentVariableNames`。三个输出字段全部必须为正数：`maxStdoutFrameBytes` 是单个 Docker `STDOUT` Frame 的 `payload.length` 上限；`maxStdoutBufferedBytes` 是尚未被 `McpStdioTransport` 消费的 stdout 字节总量上限，必须随每次实际读取的字节数递减，不能在整帧出队时提前递减；`maxStderrBytes` 是进程生命周期内累计收到的 Docker `STDERR` payload 字节总量上限，即使 stderr 已被消费也不归零。任一上限超出时不得写入导致超限的 payload，并触发一次运行失败。`snapshotId` 的唯一来源是启动事务读取到的 `McpInstallationRecord.snapshotId()`；该字段在安装确认时已由 `McpInstallationCommand` 校验为与 `McpSourceSnapshot.snapshotId()` 完全一致，运行时不得重新生成、从目录元数据推导或由请求覆盖。构造 launch spec 前，生命周期服务必须按该 `snapshotId` 读取固定快照并完成一致性校验，再把同一个值传入 runner。`workspaceMountMode` 仅允许 `NONE`、`READ_ONLY`、`READ_WRITE`；默认 `NONE`。`networkMode` 本期只允许 `NONE`。`READ_ONLY/READ_WRITE` 必须重新通过 `WorkspaceAccessService.requireWorkspace` 解析真实 `workspacePath`；用户全局安装在目标工作区启动时同样执行该检查。

`agent-sandbox` 当前不依赖 `agent-core`，因此 Docker runner 放在 `agent-web`，不放入 sandbox。新增的 `agent-web/src/main/java/com/agent/web/mcp/runtime/DockerMcpStdioRunner.java` 与 `DockerMcpStdioProcess.java` 直接实现现有 `agent-core/src/main/java/com/agent/core/tool/mcp/McpStdioProcess.java`；launch spec 直接复用现有 `com.agent.web.mcp.installation.WorkspaceMountMode`，不得再创建同名枚举。现有 `DockerCommandExecutor.bindSource` 是私有方法，`parseMounts`、`resolveWorkspaceBindSource`、`resolveContainerBindSource` 是包私有方法，web 不能直接调用；因此把这四段已验证逻辑原样抽取为 `agent-sandbox/src/main/java/com/agent/sandbox/docker/DockerWorkspaceBindResolver.java` 的公开方法，`DockerCommandExecutor` 改为委托该 resolver，web runner 也只调用该 resolver，禁止复制解析逻辑。`agent-web/pom.xml` 必须显式新增 `com.agent:agent-sandbox`、`docker-java-core`、`docker-java-transport-httpclient5` 依赖，使每个直接源码引用都有直接 Maven 依赖；`agent-sandbox/pom.xml` 不得新增 `agent-core` 或 `agent-web` 依赖，`agent-core/pom.xml` 保持现有对 `agent-sandbox` 的单向依赖。Maven reactor 构建顺序保持 `agent-sandbox`、`agent-core`、`agent-web`，不存在反向模块依赖；不新增 Maven 模块、不移动或复制 `McpStdioProcess` SPI。

`agent-web/src/main/java/com/agent/web/mcp/runtime/McpRuntimeFailureListener.java` 是最小异步失败通知端口，保持在 runner 与 Task 4 生命周期服务共同所属的 web runtime 包，不进入 core/sandbox。它是函数式接口 `void onFailure(Event event)`；嵌套不可变 `Event` 字段精确为 `installationId`、`snapshotId`、`containerId`、`reason`、`cause`，嵌套 `Reason` 枚举精确为 `ATTACH_DISCONNECTED`、`CONTAINER_EXITED`、`STDOUT_FRAME_LIMIT_EXCEEDED`、`STDOUT_BUFFER_LIMIT_EXCEEDED`、`STDERR_LIMIT_EXCEEDED`、`STREAM_IO_FAILED`。`cause` 只供进程内诊断，Task 4 持久化 `runtimeError` 和审计时只使用 `reason.name()`，不得持久化 cause 消息、stderr 或完整栈。

`DockerMcpStdioRunner.start(McpDockerLaunchSpec spec, Map<String,String> environment, Path workspacePath, McpRuntimeFailureListener failureListener)` 是唯一启动入口。`failureListener` 必填，由 `McpInstallationRuntime` 为当前 installation 构造；create/start/attach/log 在方法返回前同步失败时，由 `start` 完成 stop -> remove 后原样抛出异常，调用方同步收敛，不调用 listener。方法成功创建 process 后，输出超限或日志流 I/O 失败必须以 process 内同一个 `AtomicBoolean` 完成失败抢占，关闭三流、stop -> remove，并对该 process 精确调用一次 listener；重复 callback、并发超限和随后 `destroy()` 不得重复通知。输入 attach `onError` 是两阶段收敛：callback 线程只关闭 stdin、记录首个断连原因并异步请求 stop，绝不关闭输出日志流或抢占 process；日志 callback 继续回放尾帧，并在 `onComplete` 以该已记录原因唯一地完成 cleanup 和 `ATTACH_DISCONNECTED` listener。没有输入断连时，输出日志 callback `onComplete` inspect 当前 container：`State.Running=false` 或容器已不存在映射 `CONTAINER_EXITED`，仍为 running 映射 `ATTACH_DISCONNECTED`；日志流读写 `IOException` 映射 `STREAM_IO_FAILED`。日志 callback 自然完成后的 stdout/stderr 未读字节必须保留到消费者读取完毕，不能按 listener reason 清空。正常 `destroy()` 不调用 failure listener。listener 在清理完成后调用；listener 自身抛出的异常写脱敏运行日志并停止处理，不重试、不再次调用 listener，也不覆盖最初 failure reason。

`workspacePath` 不是 `McpDockerLaunchSpec` 字段、不是请求 DTO 字段，也不能由 MCP 快照或环境变量推导；它必须由 `McpInstallationRuntime` 在调用 runner 前取得：先按当前 actor 和请求中的目标 `workspaceId` 调用 `WorkspaceAccessService.requireWorkspace(workspaceId, actor.userId(), WorkspacePermission.OPERATOR)`，再使用返回的 `WorkspaceRecord.workspacePath()`。`USER_GLOBAL` 安装启动同样必须显式提供目标工作区并完成该校验；`WORKSPACE` 安装使用其安装记录的 `workspaceId`，请求中的目标工作区不得覆盖记录。即使 `workspaceMountMode=NONE` 也执行该工作区权限校验，runner 仅不挂载该路径；`READ_ONLY/READ_WRITE` 时 runner 使用传入路径作为 bind source，并拒绝 null。runner 不负责 actor 鉴权、不读取原始请求路径；可对传入路径执行 `toRealPath` 防御性校验，但不得改变已授权路径。

该入口使用 docker-java `createContainerCmd`、`withAttachStdin(true)`、`withAttachStdout(true)`、`withAttachStderr(true)`、`withStdinOpen(true)`、`withTty(false)`、`startContainerCmd`、仅承载 stdin 的 `attachContainerCmd(...).withFollowStream(true).withLogs(false).withStdIn(...)`，以及承载 stdout/stderr 的 `logContainerCmd(...).withFollowStream(true)`。启动顺序精确为 start -> stdin attach -> log replay；这是 Docker Desktop 对短生命周期容器稳定回放启动输出的运行时要求。日志 `Frame` 必须把 `STDOUT` 与 `STDERR` 分别写入有界管道，禁止把 stderr 混入 JSON-RPC。HostConfig 精确设置工作区 bind 的 `AccessMode.ro/rw`、`networkMode=none`、`readonlyRootfs=true`、`memoryBytes`、`nanoCpus`、`pidsLimit`、`privileged=false`；不挂 Docker socket，不使用 host network，不新增 capability。

返回的 `DockerMcpStdioProcess` 实现现有 `McpStdioProcess`；`destroy()` 按 stop -> remove 执行且幂等。标签精确包含 `com.agent.runtime.managed=true`、`com.agent.runtime.kind=mcp`、`com.agent.runtime.installation-id=<spec.installationId()>`、`com.agent.runtime.snapshot-id=<spec.snapshotId()>`；runner 只读取 launch spec 生成标签，不访问安装仓储，也不接受额外标签覆盖这四个值。Docker callback 线程只校验 Frame、复制 payload 并提交失败信号；不得在 callback 线程同步关闭 callback 自身、等待 Docker stop/remove 或调用 failure listener。失败抢占成功后必须提交到 `Executors.newVirtualThreadPerTaskExecutor()` 执行关闭 callback/三流、stop -> remove 和 listener，且 runner `close()` 必须先终止所有 process，再关闭并等待该 executor；从而避免 callback 自关闭死锁和 listener 反向调用 stop 时的递归。

Docker 运行器必须定义：镜像、容器工作目录、工作区挂载模式、无网络默认策略、CPU/内存/进程数/输出上限、环境变量白名单。密钥值来自运行时 SecretProvider，只保存环境变量名称；命令和参数只能来自已确认的 `LaunchSpec`。

新增 `agent_mcp_tool_bindings`，字段为 `installation_id`、`local_tool_name`、`remote_tool_name`、`risk_level`、`required_capabilities`、`created_at`，主键为 `(installation_id, local_tool_name)`，并对 `local_tool_name` 建唯一约束。命名空间精确为 `mcp.<installationId去除连字符>.<remoteToolName>`；若总长超过 `ToolDefinition` 的 64 字符上限则拒绝该安装启动，不能截断或猜测映射。

`ToolRegistry` 新增 `registerOwned(String ownerId, List<ToolDefinition>)`、`beginDrain(String ownerId)`、`unregisterOwned(String ownerId, Duration timeout)`。`DefaultToolRegistry` 的不可变定义快照旁保存名称到 owner 的映射、owner 生命周期与在途计数。`beginDrain` 后新调用返回明确失败；`unregisterOwned` 等待在途调用归零，超时则保持 `DRAINING` 且不关闭 client/container。现有 `register/registerAll` 委托 owner `builtin`，禁止撤销 `builtin`。

启动顺序固定为：在数据库以期望版本把 `STOPPED/FAILED` 更新为 `INSTALLING` -> 创建并 attach 容器 -> `McpClient.initialize` -> `tools/list` 全量发现 -> 把预览确认时保存的 `ToolRiskLevel` 与 `Set<RequiredCapability>` 应用于全部工具 -> `registerOwned` -> 同一数据库事务保存 bindings、runtime/container 标识并把状态置 `RUNNING`、写审计。任一步失败都 `beginDrain/unregisterOwned`、关闭 client、销毁容器，再以事务置 `FAILED` 并写失败审计。

停止顺序固定为：以期望版本 `RUNNING -> STOPPING` -> `beginDrain` -> 等待在途调用 -> `unregisterOwned` -> 关闭 client/transport -> destroy container -> 在事务内删除 binding、清空 runtime 标识、置 `STOPPED` 并写审计。卸载只能从 `STOPPED/FAILED/REJECTED` 执行，先在一个事务中写 `REMOVED` 审计并删除安装；不能直接删除 `RUNNING/INSTALLING/STOPPING`。

启动恢复器在 Spring `ApplicationReadyEvent` 后读取 `INSTALLING/RUNNING/STOPPING`。它只接管标签中 installation/snapshot 与数据库完全一致的容器；`RUNNING` 且容器存在时重新 attach、握手、发现并注册，容器缺失则事务置 `FAILED`；`INSTALLING` 统一清理残留并重启；`STOPPING` 继续 drain/销毁并置 `STOPPED`。每个安装用 JVM 内互斥与数据库 expected-status/version 更新保证重复恢复幂等。应用关闭时执行正常 stop，不把预期关闭记录为 `FAILED`。

MCP 风险元数据不能默认为低风险。安装预览请求必须显式提交 `riskLevel` 和 `requiredCapabilities`；`requiredCapabilities` 至少包含 `TOOL`，工作区可写挂载还必须包含 `CODE_WRITE`，只读挂载必须包含 `CODE_READ`。确认令牌覆盖 source commit/blob、launch spec、风险、能力和挂载策略的规范 JSON SHA-256；确认请求与预览任一字段不一致即拒绝。

## 5. GitHub 外部 Skill

只允许 GitHub 仓库来源。搜索结果不直接启用：先取得仓库默认分支的精确 commit SHA，再读取路径为 `SKILL.md` 的文件及其 blob SHA。单文件大小、UTF-8、路径和 SHA-256 必须校验；不下载可执行脚本或未声明的附加文件。

Skill 记录字段：`skillId`、`repositoryUrl`、`repository`、`commitSha`、`blobSha`、`path`、`license`、`contentSha256`、`summary`、`requestedToolNames`、`scope`、`workspaceId`、`actorUserId`、`status`。默认 `WORKSPACE`；用户显式 `USER_GLOBAL` 才允许全局安装。

安装预览展示来源、commit/blob SHA、许可证、摘要、声明工具和风险。确认后保存不可变快照。Skill 只能引用本地已注册且通过 `ToolRegistry` 能力/风险策略的工具；禁止 Skill 创建工具、执行 shell、覆盖系统 prompt 或访问安装目录之外的文件。提示词注入、未知 front matter、未知工具名均拒绝安装。

现有 `GitHubSkillContent` 只返回摘要与工具名，无法构造 `SkillDefinition`。本期扩展其受控解析结果为精确字段：front matter `name`、`version`、`description`、`triggers`、`tools` 和正文 `promptFragment`。字段集合仍是 allowlist；`version` 必须通过 `SkillDefinition` 的语义版本规则，`triggers` 和 `tools` 为字符串列表，正文继续通过 `DefaultPromptInjectionDetector`。V7 的 `agent_skill_snapshots.content` 继续是完整固定源码的持久化真源，不为上述派生字段新增数据库列；`GitHubSkillSnapshot` 和 `SkillSnapshotRecord` 保留现有持久化字段，安装预览及运行时从固定 content 使用同一个 parser 重建 `SkillDefinition`，并交叉校验 `description == summary`、`tools == requestedToolNames`。旧的无 `version/triggers` 快照不得运行，状态迁移为 `REJECTED` 并写原因审计，不能补默认值。

core 端口精确为 `SkillCatalogProvider.resolve(String actorUserId, UUID workspaceId)`，返回 `SkillCatalogSnapshot`。web 实现 `InstalledSkillCatalogProvider`：查询 `APPROVED` 且属于 `(actorUserId, workspaceId)` 的工作区安装和同一 `actorUserId` 的用户全局安装，加载对应不可变快照，重新校验 SHA-256，并把内置 Skill 与外部 `SkillDefinition` 一次性构造成 `SkillCatalogSnapshot`。repository 的聚合返回类型精确为 `InstalledSkillRecord(SkillInstallationRecord installation, SkillSnapshotRecord snapshot)`；查询按 `installation.updatedAt`、`installation.skillInstallationId` 升序返回，另提供 `Instant installationsUpdatedAt(String actorUserId, UUID workspaceId)`，其值是同一查询范围内安装记录的最大 `updatedAt`，无记录时为 `Instant.EPOCH`。名称或 trigger 冲突、工具未注册、快照校验失败时拒绝整份外部目录并写 `SKILL_CATALOG_REJECTED` 管理审计，内置目录仍可用；`detailSha256` 只覆盖稳定原因码和受影响安装 id，不写 Skill 正文。

身份绑定发生在 Web 的受信任 Run 启动边界，而不是 `ToolAgentNode` 内。`ConversationService.submitTurn` 使用当前 `Actor.userId()` 和已经通过 `WorkspaceAccessService.requireWorkspace(..., OPERATOR)` 返回的 `WorkspaceRecord.workspaceId()` 调用 provider。`CodeAgentStartRequest` 的字段精确改为 `task`、必填 `workspaceId`、`repositoryId`、`reviewerUrl`，删除 `workspacePath`；`RunController.startCodeAgent` 使用请求中的 `workspaceId` 调用 `WorkspaceAccessService.requireWorkspace(workspaceId, actor.userId(), OPERATOR)`，只使用返回记录的 `workspacePath/repositoryId/workspaceId` 构造状态和解析 Skill，旧 `workspacePath` 字段因未知字段而拒绝。通用 `RunController.start(StartRunRequest)` 明确拒绝 `code-agent` 和 `governed-cli`。任何调用方提供的 `planner.userId`、`conversation.workspaceId` 或 `skill.catalogSnapshot` 都不参与授权并被受信任入口覆盖。

冻结目录写入 `AgentState.variables` 的精确状态键为 `ToolAgentNode.SKILL_CATALOG_SNAPSHOT_KEY` (`skill.catalogSnapshot`)。值是 `SkillCatalogSnapshotCodec` 生成的 UTF-8 规范 JSON 字符串，顶层字段严格为 `schemaVersion`、`actorUserId`、`workspaceId`、`installationsUpdatedAt`、`toolRegistryRevision`、`definitions`、`snapshotSha256`：

```json
{"schemaVersion":1,"actorUserId":"user-1","workspaceId":"00000000-0000-0000-0000-000000000001","installationsUpdatedAt":"2026-08-12T00:00:00Z","toolRegistryRevision":7,"definitions":[{"name":"review-java","version":"1.2.0","description":"审查 Java 变更","triggers":["审查 Java"],"toolNames":["code.patch"],"promptFragment":"只审查当前工作区。"}],"snapshotSha256":"<64 位小写十六进制>"}
```

`definitions` 允许为空并按 `name` 升序；每个 definition 的字段顺序严格为示例顺序，`triggers` 和 `toolNames` 保留已校验快照中的顺序；时间使用 `Instant.toString()`；JSON 对象键不接受未知字段。`snapshotSha256` 是移除该字段后其余顶层对象规范 JSON UTF-8 字节的 SHA-256。`SkillCatalogSnapshotCodec.decode` 必须重新计算摘要、校验顶层 actor/workspace 与本次已绑定状态中的 `planner.userId`/`conversation.workspaceId` 精确相等，再用当前 `ToolRegistry` 构造只读 `SkillCatalog`；definitions 为空时返回“无 Skill”结果，不调用要求非空列表的 `SkillCatalog` 构造器。快照包含 Skill 定义，不包含工具 handler、secret、工作区路径或安装目录。

`ToolAgentNode` 不再持有固定 `SkillCatalog` 或在执行时查询 repository；它只从 `skill.catalogSnapshot` 解码本 Run 的不可变目录。checkpoint 创建后安装、卸载或目录缓存失效都不得改变该 Run 的提示词和暴露工具集合；进程重启恢复时继续解码 checkpoint 中同一 JSON，禁止重新调用 provider。工具定义与执行 handler 不冻结到状态中：每次调用仍按快照中的精确 `toolNames` 通过当前 `ToolRegistry.find/list/execute` 解析，工具已 drain 或撤销时本 Run 必须失败，不能使用旧 handler。`InstalledSkillCatalogProvider` 只按 `(actorUserId, workspaceId, installationsUpdatedAt, toolRegistryRevision)` 缓存冻结快照；安装/卸载、MCP 工具注册/撤销通过时间戳或 revision 形成新键，不覆盖已写入 checkpoint 的值。

Skill 正文只追加在现有 `ToolAgentNode.systemPrompt` 的“已激活 Skill”受限区，不能替换系统 prompt。`exposedDefinitions` 仍只暴露已激活 Skill 声明的工具；每次执行仍经 `ToolRegistry.execute` 的 schema、参数、capability、审批、输出脱敏与审计链。外部 Skill 不拥有独立的 secret、文件、网络或命令执行权限。

## 6. 原子事务与审计一致性

新增 V8 迁移，禁止改写已发布的 V7。V8 为 `agent_mcp_installations` 增加 `risk_level`、`required_capabilities jsonb`、`workspace_mount_mode`、`network_mode`、`runtime_image`、`container_id`、`runtime_error`、`version bigint not null default 0`，创建 `agent_mcp_tool_bindings`；为 `agent_skill_installations` 增加 `version bigint not null default 0`。已有 MCP 安装在无法证明治理元数据时保持 `STOPPED`，必须重新预览确认后才能启动。

repository 不再暴露由 service 串联的 `saveSnapshot` + `saveInstallation`。新增聚合命令 `confirmInstallation(...)`、`transition(...)`、`confirmSkill(...)`、`removeSkill(...)`，每个实现内部使用已有 `TransactionTemplate` 在同一个事务中完成快照 upsert、安装写入/状态迁移、binding 变更和 `agent_capability_management_audit` 插入。业务事务不能调用独立的 `CapabilityManagementAuditSink` 形成第二次提交；Sink 仅保留给没有业务表变更的读取/运行诊断事件。

每次状态迁移 SQL 必须包含 `where installation_id=:id and version=:expectedVersion and status in (...)` 并原子 `version=version+1`。更新数不为 1 返回冲突，不能覆盖另一个启动/停止/恢复操作。确认令牌在事务成功后才从内存移除；事务回滚后允许使用同一未过期预览重试。Skill 同理。

审计事件增加 `operation_id`、`from_status`、`to_status`、`detail_sha256`；仍不记录 secret 值、Skill 正文、MCP stdout/stderr 或完整工具参数。运行错误只保存异常类型、稳定错误码和脱敏摘要，完整栈进入现有日志。

## 7. CLI 工作台与专用 Run

现有 `CliCommandDefinition` 的精确字段为：`name`、`executable`、`fixedArguments`、`riskLevel`、`requiredCapabilities`。本期不扩展该核心 record，也不引入未经现有源码验证的描述、命名参数或参数类型字段。命令目录 API 返回上述五个字段，并增加由服务根据 `CliCommandIntent` 上限返回的 `maxArguments=64`。前端按 `riskLevel` 展示审批状态：`READ_ONLY` 为自动允许，`MUTATING` 为等待用户批准；`DESTRUCTIVE` 不出现在首期目录。`fixedArguments` 是不可变的字符串 token 列表，按定义顺序渲染在 executable 之后。

CLI Run 请求字段精确为：`commandName`、`arguments`、`timeoutSeconds`。其中 `arguments` 是与 `CliCommandIntent.arguments` 相同的有序 `List<String>`；每个元素都是一个完整 token，不允许 null、空 token、控制字符或 Shell 控制字符（`;`、`&`、`|`、`<`、`>`、反引号、`$`），最多 64 个元素，服务不得把它解释为 Shell 片段。`timeoutSeconds` 必须为 1 至 600 的整数。请求拒绝 `approval`、`shell`、`bashCommand`、未声明字段和工作区外路径。服务使用当前 `WorkspaceAccessService` 返回的 `workspacePath` 和 `WorkspaceTerminalTargetResolver` 构造 `CliCommandIntent`，并把唯一授权入口交给 `CliCommandCatalog.authorize`。

专用 graphId 精确为 `governed-cli`，图只注册现有 `ops` 节点：入口为 `ops`，执行完成后到 `StateGraph.END`。创建 Run 前写入状态变量：`OpsNode.COMMAND_NAME_KEY` (`ops.commandName`)、`OpsNode.COMMAND_ARGUMENTS_KEY` (`ops.commandArguments`，JSON 字符串数组)、新增 `OpsNode.COMMAND_TIMEOUT_SECONDS_KEY` (`ops.commandTimeoutSeconds`，十进制整数字符串)、`CoderNode.WORKSPACE_PATH_KEY` (`coder.workspacePath`) 和 `PlannerNode.REQUIRED_CAPABILITIES_KEY` (`planner.requiredCapabilities`，由命令定义的 `requiredCapabilities` 按 `RequiredCapability` 枚举声明顺序连接为逗号分隔名称)。`CliApprovalInterruptPolicy.parse` 必须从 `ops.commandTimeoutSeconds` 构造 `Duration.ofSeconds`，重新执行 1 至 600 校验，并把同一 `Duration` 放入最终 `CliCommandIntent`；不得再用构造器中的 `ProductionAgentProperties.commandTimeout()` 覆盖请求值。构造器固定 timeout 只保留给没有状态字段的非 `governed-cli` 兼容路径；`governed-cli` 缺少或篡改该字段时失败，不回退默认值。

进入 `ops` 前由 `CliApprovalInterruptPolicy.evaluate` 调用目录授权：`READ_ONLY` 直接运行；`MUTATING` 产生 `RunStatus.WAITING_APPROVAL`。`CliCommandController.list` 与 `start` 都必须拒绝 `CliRiskLevel.DESTRUCTIVE`：列表过滤，直接按名称提交时返回稳定错误，不能只依赖前端隐藏。中断详情使用现有 `InterruptRequest` 字段，至少包含 `commandName`、`commandArguments`、渲染后的 `command`、`riskLevel`、`commandSha256`、`timeoutSeconds` 和 `authorizationReason`。批准/拒绝只能调用现有 `POST /api/runs/{runId}/approval`，提交 `ApprovalRequest { decision, expectedVersion, reason, variableUpdates }` 并由 `AgentRunService.decide` 处理；本期 `variableUpdates` 必须为空，现有通用 `ApprovalDialog` 对 `governed-cli` 必须隐藏“修改”动作。批准恢复 `ops`，拒绝得到 `RunStatus.REJECTED`。日志继续由 `RunTerminalController` 的 `/api/runs/{runId}/logs` 和 Trace `/api/runs/{runId}/events` 提供，CLI E2E 必须用同一 `runId` 断言审批、终端日志与 Trace。

## 8. 精确管理 API

- `GET /api/mcp/catalog` -> `CatalogView { repository, commitSha, fetchedAt, expiresAt, etag, status, servers, errors }`
- `POST /api/mcp/catalog/refresh` -> `CatalogRefreshView { status, commitSha, fetchedAt, expiresAt }`
- `GET /api/workspaces/{workspaceId}/mcp/installations` -> `List<InstallationView>`；每项增加 `riskLevel`、`requiredCapabilities`、`workspaceMountMode`、`networkMode`、`runtimeState`、`runtimeError`、`version`。
- `POST /api/workspaces/{workspaceId}/mcp/installations/preview` 请求 `PreviewRequest { serverKey, scope, targetWorkspaceId, riskLevel, requiredCapabilities, workspaceMountMode }`，返回 `InstallationPreview { previewId, source, launchSpec, environmentNames, riskLevel, requiredCapabilities, workspaceMountMode, networkMode, summary, requiresConfirmation, sideEffectFree }`
- `POST /api/workspaces/{workspaceId}/mcp/installations` 请求 `ConfirmInstallationRequest { previewId, confirmationToken, scope, targetWorkspaceId }`，返回 `InstallationView`
- `POST /api/workspaces/{workspaceId}/mcp/installations/{installationId}/start` 请求 `LifecycleRequest { expectedVersion, environment }`；`environment` 只允许预览声明的变量名，值不回显、不落库。
- `POST /api/workspaces/{workspaceId}/mcp/installations/{installationId}/stop` 请求 `LifecycleRequest { expectedVersion, environment={} }`。
- `DELETE /api/workspaces/{workspaceId}/mcp/installations/{installationId}?expectedVersion=...` -> `InstallationView`。
- `GET /api/workspaces/{workspaceId}/skills`、`GET /api/skills/search?q=...`、`POST /api/workspaces/{workspaceId}/skills/preview`、`POST /api/workspaces/{workspaceId}/skills`、`DELETE /api/workspaces/{workspaceId}/skills/{skillId}`，字段严格对应第 5 节。
- `GET /api/workspaces/{workspaceId}/cli/commands`、`POST /api/workspaces/{workspaceId}/cli/runs`，字段严格对应第 6 节。

管理审计使用新增 `CapabilityManagementAuditEvent`/`CapabilityManagementAuditSink`，独立于会话审计；事件包含 `eventType`、`actorUserId`、`workspaceId`、`installationId/skillId`、`runId`、`sourceCommitSha`、`result`、`occurredAt`，严禁写入密钥值。数据库继续使用 UTC `timestamptz`，展示层转换 `Asia/Shanghai`。

## 9. 前端与验收

现有 `CapabilityWorkbenchRuntime`/`CapabilityWorkbenchPanel` 已由工作台挂载。本里程碑扩展其安装列表和生命周期：展示 `STOPPED/INSTALLING/RUNNING/STOPPING/FAILED`、版本、风险、能力、挂载与无网络策略；只有合法状态显示启动、停止、重试、卸载按钮。启动弹窗只为声明的环境变量渲染 secret 输入，提交后立即清空，不进入 React 持久状态、URL、日志或错误文本。轮询或事件刷新必须以 API 返回的 `version` 抑制旧响应覆盖新状态。

Skill 区显示当前工作区与用户全局的已安装列表、`APPROVED/REJECTED/REMOVED`、来源 commit、工具声明和“已进入当前 Agent”状态。卸载后下一轮对话不得出现该 Skill；进行中的 Run 保留启动时解析出的不可变目录快照，避免中途 prompt/工具集合漂移。

测试必须覆盖 DTO 精确字段、工作区权限、预览无副作用、固定 SHA/ETag/TTL/限流、Docker 启停与恢复、stdio 并发帧/通知/退出、按安装撤销、Skill 供应链拒绝、伪造 Run 身份状态拒绝、Skill Run 冻结与 checkpoint 恢复、CLI 请求超时贯穿执行链、`DESTRUCTIVE` 过滤、`MUTATING` 批准/拒绝、同一 Run 的终端日志/Trace 和前端真实挂载。

真实 EDD 使用仓库已有入口：

```powershell
pwsh .agent4j/acceptance/run-real-agent.ps1
pwsh .agent4j/acceptance/run-conversation-continuity.ps1
mvn -pl agent-eval -am -Dgroups=edd -Dtest=LlmEddTest test
```

只有报告中存在 `modelCallAttempts > 0`、真实 HTTP 记录、Run/Trace/Audit 证据且所有场景通过，才可称为真实 EDD；缺少 API 配置只能标记跳过，不能冒充通过。
