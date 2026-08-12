# MCP and Skill Runtime Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Each task requires implementation, spec review, then code-quality review.

**Goal:** 让已经确认并持久化的官方 MCP 与 GitHub Skill 真正按用户/工作区边界进入 Agent 运行时，并完成可停止、可撤销、可恢复、可审计的产品闭环。

**Architecture:** 保留现有官方目录、预览确认、V7、管理 API、CLI 与 `McpStdioTransport`。新增 docker-java 持续 stdio 运行器、owner-scoped 工具注册、V8 乐观锁事务、按请求解析的 Skill 目录和前端生命周期控制；任何运行时能力都从固定快照恢复，不从公网即时执行。

**Tech Stack:** Java 21、Spring Boot 3.3、docker-java 3.7.1、PostgreSQL/Flyway、React/TypeScript、JUnit 5、Testcontainers。

---

## 已交付基线

以下代码已有实现，本计划只在需要运行时契约时修改，不重新开发：

- `OfficialMcpCatalogClient`、`GitHubSkillCatalogClient`
- `McpInstallationService`、`GitHubSkillInstallationService`
- `V7__create_mcp_catalog_installations.sql`
- `CapabilityManagementController`、`CapabilityWorkbenchRuntime`
- `McpStdioTransport`、`McpStdioProcess`
- `CliCommandController` 和聊天 `/` 命令选择器

### Task 1: V8 原子安装聚合与治理元数据

**Files:**
- Create: `agent-web/src/main/resources/db/migration/V8__complete_capability_runtime.sql`
- Create: `agent-web/src/main/java/com/agent/web/mcp/installation/McpInstallationCommand.java`
- Modify: `agent-web/src/main/java/com/agent/web/mcp/installation/McpInstallationRecord.java`
- Modify: `agent-web/src/main/java/com/agent/web/mcp/installation/McpSourceSnapshot.java`
- Modify: `agent-web/src/main/java/com/agent/web/mcp/installation/McpInstallationRepository.java`
- Modify: `agent-web/src/main/java/com/agent/web/skill/SkillInstallationRepository.java`
- Modify: `agent-web/src/main/java/com/agent/web/persistence/JdbcMcpInstallationRepository.java`
- Modify: `agent-web/src/main/java/com/agent/web/persistence/JdbcSkillInstallationRepository.java`
- Test: `agent-web/src/test/java/com/agent/web/persistence/JdbcMcpInstallationRepositoryTest.java`
- Test: `agent-web/src/test/java/com/agent/web/persistence/JdbcSkillInstallationRepositoryTest.java`

- [ ] 写 PostgreSQL 集成测试：快照、安装、审计在同一事务提交；审计插入失败时三者全部回滚；相同 `expectedVersion` 只有一次状态迁移成功。
- [ ] 新增 V8：MCP 安装增加 `risk_level`、`required_capabilities`、`workspace_mount_mode`、`network_mode`、`runtime_image`、`container_id`、`runtime_error`、`version`；Skill 安装增加 `version`；创建 `agent_mcp_tool_bindings`；审计增加 `operation_id`、`from_status`、`to_status`、`detail_sha256`。
- [ ] 把 repository 契约改为事务聚合方法 `confirmInstallation`、`transition`、`confirmSkill`、`removeSkill`；状态 SQL 必须带 installation id、expected version 和 allowed status。
- [ ] 修改两个 confirm service：事务成功后才移除一次性 preview；回滚后同一未过期 token 可重试。禁止业务写事务再调用独立审计事务。
- [ ] 运行 `mvn -pl agent-web -am -Dtest=JdbcMcpInstallationRepositoryTest,JdbcSkillInstallationRepositoryTest,McpInstallationServiceTest,GitHubSkillInstallationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，预期全部 PASS。
- [ ] 提交 `feat(capabilities): make installation changes atomic`。

### Task 2: docker-java 持续 MCP stdio 运行器

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/mcp/runtime/McpDockerLaunchSpec.java`
- Create: `agent-web/src/main/java/com/agent/web/mcp/runtime/DockerMcpStdioRunner.java`
- Create: `agent-web/src/main/java/com/agent/web/mcp/runtime/DockerMcpStdioProcess.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/docker/DockerWorkspaceBindResolver.java`
- Modify: `agent-sandbox/src/main/java/com/agent/sandbox/docker/DockerCommandExecutor.java`
- Modify: `agent-web/pom.xml` (add direct `agent-sandbox` and docker-java dependencies)
- Test: `agent-web/src/test/java/com/agent/web/mcp/runtime/DockerMcpStdioRunnerTest.java`
- Test: `agent-core/src/test/java/com/agent/core/tool/mcp/McpStdioTransportTest.java`

- [ ] 写 runner contract test，使用固定 `installationId` 和固定 `snapshotId` 构造 launch spec，精确断言 create 参数包含 stdin/stdout/stderr attach、stdin open、TTY false、network `none`、readonly rootfs、非 privileged、memory/nano CPUs/pids、受控 bind，以及分别来自 `spec.installationId()`、`spec.snapshotId()` 的四个管理标签。
- [ ] 写流测试：docker `Frame` 的 STDOUT/STDERR 分离；stdin 字节传到 attach 输入；并发响应、帧/错误输出上限、attach 断开、容器退出、重复 destroy 均确定完成。
- [ ] 保持现有 SPI 不动：`agent-core/src/main/java/com/agent/core/tool/mcp/McpStdioProcess.java` 继续作为 runner 的公共端口；`DockerMcpStdioRunner` 位于 `agent-web`，直接实现该 SPI。`agent-web/pom.xml` 显式依赖 `agent-sandbox`、`docker-java-core`、`docker-java-transport-httpclient5`；`agent-sandbox/pom.xml` 不得新增 core/web 依赖，不创建第二份 SPI。
- [ ] 实现不可变 `McpDockerLaunchSpec`，字段必须严格等于规范第 4 节列表（包括必填 `snapshotId`，不含 `workspacePath`）和 `NONE/READ_ONLY/READ_WRITE`；command/arguments 直接传 `withCmd`，禁止 `bash -lc`、`ProcessBuilder` 和字符串拼接 shell。Task 2 测试直接传入固定 `snapshotId`；Task 4 生命周期服务必须从已持久化的 `McpInstallationRecord.snapshotId()` 取得该值，按它读取并校验固定 `McpSourceSnapshot` 后构造 spec，禁止重新生成、从目录元数据推导或由启动请求覆盖。
- [ ] 固定唯一入口 `DockerMcpStdioRunner.start(McpDockerLaunchSpec spec, Map<String,String> environment, Path workspacePath)`。Task 4 在调用前使用 `WorkspaceAccessService.requireWorkspace(targetWorkspaceId, actor.userId(), WorkspacePermission.OPERATOR).workspacePath()` 取得路径；不得使用请求原始路径、安装快照路径或环境变量。即使挂载模式为 `NONE` 也必须做权限校验；runner 仅在 `READ_ONLY/READ_WRITE` 使用第三参数并拒绝 null，按 `AccessMode.ro/rw` 创建 bind。
- [ ] 把 `DockerCommandExecutor` 中现有私有/包私有 bind 解析逻辑抽取为 sandbox 的公开 `DockerWorkspaceBindResolver`，由 executor 和 web runner 共同委托；不得复制逻辑，且不得改变一次性 executor 的 finally-delete 语义。在 `agent-web` 实现 runner/process，直接使用 docker-java 持久容器 API，并复用现有 `com.agent.web.mcp.installation.WorkspaceMountMode`。
- [ ] 在测试中 inspect 实际容器并断言 mount access、HostConfig、labels 和容器销毁；Docker 不可用时测试明确 SKIP，不能伪造 PASS。
- [ ] 运行 `mvn -pl agent-web -am -Dtest=DockerMcpStdioRunnerTest,McpStdioTransportTest -Dsurefire.failIfNoSpecifiedTests=false test`，预期全部 PASS 或只有带原因的 Docker SKIP。
- [ ] 提交 `feat(mcp): add persistent docker stdio runner`。

### Task 3: 按安装 owner 注册、drain 与撤销工具

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/tool/ToolRegistry.java`
- Modify: `agent-core/src/main/java/com/agent/core/tool/DefaultToolRegistry.java`
- Modify: `agent-core/src/main/java/com/agent/core/tool/mcp/McpToolRegistryAdapter.java`
- Create: `agent-core/src/main/java/com/agent/core/tool/ToolOwnerState.java`
- Test: `agent-core/src/test/java/com/agent/core/tool/ToolRegistryOwnershipTest.java`
- Test: `agent-core/src/test/java/com/agent/core/tool/mcp/McpToolRegistryAdapterTest.java`

- [ ] 先测试 `registerOwned(ownerId, definitions)` 全批原子、跨 owner 名称冲突、`beginDrain` 后拒绝新调用、在途调用归零后撤销、超时保持 DRAINING、不能撤销 `builtin`。
- [ ] 在 `ToolRegistry` 增加 `registerOwned(String,List<ToolDefinition>)`、`beginDrain(String)`、`unregisterOwned(String,Duration)` 和只读 `revision()`；现有 register 方法委托 `builtin`。
- [ ] `DefaultToolRegistry.execute` 在选择定义时原子增加 owner 在途计数，并在所有成功/失败/超时路径 finally 减少；定义、owner、状态作为单一不可变发布快照更新。
- [ ] `McpToolRegistryAdapter` 返回发现的 local/remote binding，并用 owner 注册；本地名精确使用 `mcp.<UUID去连字符>.<remoteName>`，超过 64 字符直接失败。
- [ ] 运行 `mvn -pl agent-core -Dtest=ToolRegistryOwnershipTest,McpToolRegistryAdapterTest,ToolRegistryConcurrencyTest test`，预期全部 PASS。
- [ ] 提交 `feat(tools): support installation scoped lifecycle`。

### Task 4: MCP 启停、失败收敛与重启恢复

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/mcp/runtime/McpInstallationRuntime.java`
- Create: `agent-web/src/main/java/com/agent/web/mcp/runtime/McpRuntimeRecovery.java`
- Create: `agent-web/src/main/java/com/agent/web/mcp/runtime/McpRuntimeSecretProvider.java`
- Modify: `agent-web/src/main/java/com/agent/web/mcp/installation/McpInstallationService.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/CapabilityManagementController.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`
- Test: `agent-web/src/test/java/com/agent/web/mcp/runtime/McpInstallationRuntimeTest.java`
- Test: `agent-web/src/test/java/com/agent/web/mcp/runtime/McpRuntimeRecoveryTest.java`
- Test: `agent-web/src/test/java/com/agent/web/controller/CapabilityManagementControllerTest.java`

- [ ] 写生命周期测试覆盖 `STOPPED -> INSTALLING -> RUNNING -> STOPPING -> STOPPED`、每一步失败到 FAILED、expectedVersion 冲突、未准备物料 `MATERIAL_NOT_PREPARED`、secret 名称白名单和值不落库。
- [ ] 启动时重新校验 actor/workspace 权限和固定 snapshot/material SHA，创建 runner/client，握手分页发现，应用已确认 risk/capabilities，owner 注册成功后在一个事务写 binding、container id、RUNNING 和审计。
- [ ] 停止时先状态迁移，再 drain、撤销、关闭 client、销毁容器，最后事务置 STOPPED；卸载拒绝运行态。
- [ ] 恢复测试覆盖带匹配标签容器的 RUNNING 重连、缺失容器转 FAILED、INSTALLING 清理重启、STOPPING 继续停止、重复 ApplicationReadyEvent 幂等、应用正常关闭不记 FAILED。
- [ ] API 增加 start/stop 和 expectedVersion；响应增加风险、能力、挂载、网络、runtime state/error/version。错误响应不含 secret 值、stderr 或完整栈。
- [ ] 运行 `mvn -pl agent-web -am -Dtest=McpInstallationRuntimeTest,McpRuntimeRecoveryTest,CapabilityManagementControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`，预期全部 PASS。
- [ ] 提交 `feat(mcp): complete installation runtime lifecycle`。

### Task 5: 已安装 Skill 按用户和工作区进入 Agent

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/skill/GitHubSkillContent.java`
- Modify: `agent-web/src/main/java/com/agent/web/skill/SkillSnapshotRecord.java`
- Modify: `agent-web/src/main/java/com/agent/web/skill/SkillInstallationRepository.java`
- Create: `agent-core/src/main/java/com/agent/core/skill/SkillCatalogProvider.java`
- Create: `agent-web/src/main/java/com/agent/web/skill/InstalledSkillCatalogProvider.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/ToolAgentNode.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Test: `agent-web/src/test/java/com/agent/web/skill/InstalledSkillCatalogProviderTest.java`
- Test: `agent-core/src/test/java/com/agent/core/nodes/ToolAgentNodeTest.java`

- [ ] 扩展受控 SKILL.md parser 测试：只接受 `name/version/description/triggers/tools`，正文作为 promptFragment；未知字段、非法 semver、重复 trigger、提示词注入、未注册工具全部拒绝。
- [ ] repository 增加一次查询当前 actor 的 WORKSPACE APPROVED 与 USER_GLOBAL APPROVED 安装及快照；不返回其他用户或其他工作区记录。
- [ ] 实现 `InstalledSkillCatalogProvider.resolve(actorUserId, workspaceId)`：校验 content SHA，组合内置与已安装 definition，冲突时隔离外部目录并审计；缓存键含 actor、workspace、安装更新时间和 `ToolRegistry.revision()`。
- [ ] `ToolAgentNode` 用 provider 读取精确状态键 `planner.userId` 和 `conversation.workspaceId`；Run 启动后持有不可变目录快照，安装/卸载不改变进行中的提示词和工具集。
- [ ] 测试 A 用户/A 工作区 Skill 不出现在 B 上下文；卸载后下一 Run 不再发现；Skill 激活只暴露声明工具且调用仍经过 ToolRegistry 治理。
- [ ] 运行 `mvn -pl agent-web -am -Dtest=InstalledSkillCatalogProviderTest,ToolAgentNodeTest,SkillMcpIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，预期全部 PASS。
- [ ] 提交 `feat(skill): load approved installations into agents`。

### Task 6: 生命周期 UI、端到端与真实 EDD

**Files:**
- Modify: `agent-web/src/main/frontend/src/api/capabilityApi.ts`
- Modify: `agent-web/src/main/frontend/src/components/CapabilityWorkbenchPanel.tsx`
- Modify: `agent-web/src/main/frontend/src/components/CapabilityWorkbenchRuntime.tsx`
- Test: `agent-web/src/main/frontend/src/components/CapabilityWorkbenchPanel.test.tsx`
- Test: `agent-web/src/test/java/com/agent/web/ProductWorkbenchLifecycleIntegrationTest.java`
- Create: `agent-eval/src/test/resources/benchmarks/mcp-skill-runtime.json`
- Modify: `README.md`

- [ ] 前端 decoder 先测试精确的新 DTO 字段和未知字段拒绝；面板测试状态按钮、expectedVersion、风险/能力/挂载显示、secret 输入提交后清空、旧 version 响应不覆盖新状态。
- [ ] 实现安装列表、启动/停止/重试/卸载；仅合法状态显示动作。Skill 列表显示 scope、commit、工具和当前 Agent 可用状态。
- [ ] 用受控 fixture 镜像完成 Docker E2E：确认安装、启动、真实 tools/list、Agent function call、工具 Trace/Audit、停止后不可调用、应用重启恢复、卸载。
- [ ] 用两个用户和两个工作区完成 Skill 隔离 E2E；断言激活证据 `skill.active`、`skill.fingerprint` 和工具调用均来自固定快照。
- [ ] 运行前端 `Set-Location agent-web/src/main/frontend; .\.frontend\node\npm.cmd run test:run`，运行后端 `mvn clean verify`。
- [ ] 使用已配置 LLM 执行 `pwsh .agent4j/acceptance/run-real-agent.ps1` 与 `mvn -pl agent-eval -am -Dgroups=edd -Dtest=LlmEddTest test`；报告必须有 `modelCallAttempts > 0`、真实 HTTP、MCP tool call、Run/Trace/Audit，缺少配置只能 SKIP。
- [ ] 运行 `git diff --check` 和 `git status --short`，确认不提交 `.env`、日志、target；完成 Sol high 规格审查与质量复审。
- [ ] 提交 `feat(web): deliver mcp and skill runtime workbench`。
