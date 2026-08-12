# Official MCP, GitHub Skill and Governed CLI Workbench Implementation Plan

> 仅在本计划审查通过后实施。每项任务先写测试，再实现；禁止修改未列出的无关模块。

## Task 1: 固定 SHA 的官方目录客户端

文件：`agent-web/src/main/java/com/agent/web/mcp/catalog/OfficialMcpCatalogClient.java`、`OfficialMcpServerRecord.java`、测试与固定 JSON fixtures。

- [ ] 先测试根/`src/` Contents、commit SHA 与 blob SHA、ETag/304、响应大小、TTL、限流和未知字段拒绝。
- [ ] 实现固定 commit ref 的 Contents/Raw 读取；按实际目录枚举，不把七个名称硬编码为成功条件。
- [ ] 结构化解析 TypeScript `package.json` 的 `name/version/description/license/bin`；Python `pyproject.toml` 的 `[project]` 和 `[project.scripts]`。README 仅保存摘要和展示证据。
- [ ] 使用 `Executors.newVirtualThreadPerTaskExecutor()`，配置连接/读取超时与最大字节数；刷新失败返回 stale cache 或 `CATALOG_UNAVAILABLE`。
- [ ] 运行 `mvn -pl agent-web -am -Dtest=OfficialMcpCatalogClientTest -Dsurefire.failIfNoSpecifiedTests=false test`，提交 `feat(mcp): add fixed-sha catalog client`。

## Task 2: MCP/Skill 缓存、安装记录与独立管理审计

文件：`V7__create_mcp_catalog_installations.sql`、新增 MCP/Skill record/repository/service、管理审计端口及测试。

- [ ] 表使用 `agent_mcp_catalog_snapshots`、`agent_mcp_installations`、`agent_skill_snapshots`、`agent_skill_installations`、`agent_capability_management_audit`；UTC `timestamptz`、状态 check、workspace/user 索引、SHA 唯一约束。
- [ ] 安装 scope 精确为 `WORKSPACE`/`USER_GLOBAL`；工作区默认绑定 `workspaceId + actorUserId`，全局必须显式选择且 `workspaceId` 为空。
- [ ] 测试 preview 无写入/下载/启动，确认 token 一次性，固定 commit/blob/SHA-256 快照不可变，环境变量只保存名称。
- [ ] Skill 搜索仅使用 GitHub API；读取固定 commit 的 `SKILL.md`，拒绝超限、非 UTF-8、未知工具、提示词注入和可执行附加文件。
- [ ] 运行 focused tests，提交 `feat(capabilities): persist governed mcp and skills`。

## Task 3: Docker 隔离 MCP stdio 与按安装注册撤销

文件：新增 `McpStdioTransport`（实现现有 `McpTransport`）、Docker 运行器、安装运行时及测试。

- [ ] 测试 stdout JSON-RPC 帧、stderr 分离、通知、响应 ID 并发、未知 ID、超时、进程退出、输出上限和清理。
- [ ] 运行器只接受已确认 `LaunchSpec`；通过 Docker API 启动，工作区挂载限定到 `WorkspaceAccessService` 返回路径，默认无网络，固定资源上限；禁止本机 `ProcessBuilder`。
- [ ] 增加 `ToolRegistry` 安装实例绑定/撤销端口；启动发现失败整批回滚，停止先拒绝新调用并等待在途调用，再撤销绑定、关闭 client/container。
- [ ] 启动时恢复 `INSTALLING/RUNNING`，保证幂等；退出/协议错误置 `FAILED` 并写管理审计。
- [ ] 运行 core/web focused tests，提交 `feat(mcp): run approved servers in docker`。

## Task 4: MCP 与 Skill 管理 API 和前端挂载

文件：新增 Controller/View，修改 `conversationApi.ts`、`contracts.ts`，新增 `McpCatalogPanel.tsx` 和测试，修改 `Workbench.tsx`。

- [ ] 严格实现设计文档第 7 节 DTO、未知字段拒绝、HTTP 状态码和工作区权限。
- [ ] 预览展示来源、commit/blob SHA、许可证、启动配置、环境变量名、风险、工具/Skill 权限和确认状态。
- [ ] 面板由 `Workbench.tsx` 实际挂载，覆盖搜索、预览、确认、卸载、失败恢复和全局显式选择。
- [ ] 运行前端 focused tests，提交 `feat(web): mount capability workbench`。

## Task 5: 专用 `governed-cli` Graph 与结构化 CLI Run API

文件：新增 CLI Controller/View/Request、CLI GraphFactory/Node、配置与测试；扩展 `CliCommandDefinition` 的 `description/arguments` 并迁移全部构造调用。

- [ ] 目录 API 只读返回真实命令字段；请求只允许 `commandName/arguments/timeoutSeconds/approval`，拒绝 `shell/bashCommand`、未知字段和工作区外路径。
- [ ] 新增精确 graphId `governed-cli`，状态写入 `OpsNode.COMMAND_NAME_KEY`、`OpsNode.COMMAND_ARGUMENTS_KEY`、`CoderNode.WORKSPACE_PATH_KEY`、`PlannerNode.REQUIRED_CAPABILITIES_KEY`。
- [ ] READ_ONLY 直接执行；MUTATING 使用现有 `CliApprovalInterruptPolicy` 生成 `WAITING_APPROVAL`，批准后使用 `AgentRunService.decide`。DESTRUCTIVE 首期从目录排除并测试拒绝。
- [ ] 绑定 `RunTerminalController` `/api/runs/{runId}/logs`、`RunTraceController` `/api/runs/{runId}/events`，新增管理审计事件。
- [ ] 运行 `mvn -pl agent-web -am -Dtest=CliCommandControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`，提交 `feat(cli): add governed cli workbench api`。

## Task 6: 聊天 `/` 命令选择器

文件：修改 `ConversationComposer.tsx`、`Workbench.css`、前端 API/contracts；新增组件测试。

- [ ] `/` 和 `/` 后缀触发当前 workspace 命令目录；键盘选择、参数输入、风险/审批状态和错误提示可访问。
- [ ] 选中命令后调用 `POST /api/workspaces/{workspaceId}/cli/runs`，使用返回 `runId` 接入现有 `useRunWorkbench.followRun`；普通会话提交回归测试。
- [ ] 运行 `Set-Location agent-web/src/main/frontend; .\.frontend\node\npm.cmd run test:run`，提交 `feat(web): add slash cli picker`。

## Task 7: 集成、真实 EDD 与交付审查

文件：新增 MCP/CLI 集成测试、`agent-eval/src/test/resources/benchmarks/mcp-cli-workbench.json`、README 更新。

- [ ] 使用内置 Docker stdio fixture 验证握手、分页、工具调用、停止撤销、重启恢复和审计/Trace。
- [ ] EDD 场景覆盖目录刷新失败、未确认安装拒绝、确认工作区安装、Skill 供应链拒绝、CLI slash 提交、MUTATING 审批、失败恢复。
- [ ] 运行确定性 EDD：`mvn -pl agent-eval -am -Dgroups=edd -Dtest=McpToolAdapterEddTest,SkillCatalogEddTest,CliCapabilityEddTest,CliAgentWorkflowEddTest test`。
- [ ] Docker 与真实模型验收严格使用 README 的 `pwsh .agent4j/acceptance/run-real-agent.ps1`、`pwsh .agent4j/acceptance/run-conversation-continuity.ps1` 和 `mvn -pl agent-eval -am -Dgroups=edd -Dtest=LlmEddTest test`；报告必须含真实 HTTP、`modelCallAttempts > 0`、Run/Trace/Audit 证据。
- [ ] 运行 `mvn clean verify`、前端测试、`git diff --check`、`git status --short`，确认不提交 `.env`、日志或 target；完成 Sol high 规格/质量复审后提交 `feat(mcp): deliver governed capability workbench`。
