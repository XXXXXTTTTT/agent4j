# Agent4J CLI 与多工作区产品闭环设计

## 目标

将现有 Harness 工作台升级为可以日常使用的 Code Agent 产品：

- 每个会话绑定一个工作区，每个 Run 在该工作区内读取、修改和验证代码。
- 提供本机 CLI，从当前目录启动或连接 Agent4J，创建和恢复持久化会话。
- Web 提供工作区创建、切换、会话管理、对话、Diff、终端、Trace、审批和浏览器证据。
- Docker 快速启动继续保留，CLI 负责把用户选择的宿主目录精确挂载到容器。
- PostgreSQL 继续作为工作区、会话、轮次和 Run Checkpoint 的唯一权威源。

本设计不复制 Codex 或 Claude 的品牌、文案和私有实现，只实现同类别的核心交互能力。

## 当前缺口

当前数据库和 REST API 已支持多个工作区，但生产图仍通过
`ProductionAgentProperties.workspace` 在应用启动时创建单一 `TerminalTarget`。
`ConversationService` 会把工作区路径写入精确状态键 `coder.workspacePath`，`CoderNode`
读取该值；`CliApprovalInterruptPolicy` 和 `OpsNode` 却继续使用启动时固定目标。因此 Web
切换工作区只切换了会话数据，不能保证终端命令在相同工作区执行。

当前前端已经具备持久化会话侧栏、输入框、Run 跟随、Monaco Diff、xterm.js、Trace、
HITL 和 Reviewer 证据，但缺少工作区创建入口和挂载边界说明。项目没有面向用户的 CLI
模块，`agent-core` 中的 `cli` 包只负责工具命令治理，不是交互式客户端。

普通浏览器不会向页面暴露用户选择目录的宿主机绝对路径，运行中的 Docker 容器也不能
动态增加 bind mount。因此 Web 不能独立完成任意宿主目录绑定。任意本地目录由本机 CLI
验证后作为 `AGENT_CODE_HOST_WORKSPACE` 启动 Compose；Web 只创建和选择 Agent 服务已经
能够访问的目录。

## 方案对比

### 方案 A：仅增加 Web 目录输入框

改动最少，但 Docker 内看到的是容器路径，用户输入 `D:\project` 时后端无法访问该目录。
这种方案会产生可点击但不可执行的入口，不采用。

### 方案 B：浏览器读取目录并上传副本

可绕过 Docker mount，但会复制完整仓库，带来大文件、`.git`、符号链接、权限、增量同步
和删除语义。它适合远程 SaaS 导入，不适合本项目当前的本机 Agent 目标，不采用。

### 方案 C：本机 CLI 绑定目录，Web 管理已挂载工作区

CLI 拥有本机路径和进程权限，可以精确校验目录并设置 Compose bind mount；后端在挂载根
目录内管理多个子工作区；Web 与 CLI 共用相同 REST、SSE、WebSocket 和 PostgreSQL 数据。
该方案没有文件副本，代码修改直接落到用户仓库，确定采用。

## 里程碑

### 里程碑一：Run 级工作区执行目标

在 `agent-core` 新增 `WorkspaceTerminalTargetResolver`：

```java
@FunctionalInterface
public interface WorkspaceTerminalTargetResolver {
    TerminalTarget resolve(Path workspacePath);
}
```

`CliApprovalInterruptPolicy` 不再保存固定 `TerminalTarget`，而是保存 resolver。解析状态中的
精确键 `coder.workspacePath` 后，将同一个路径同时用于 `CliCommandIntent.workspace` 与
resolver，确保授权目录和实际执行目录一致。保留接收固定 `TerminalTarget` 的现有构造器，
避免破坏已有测试和嵌入方。

`ProductionGraphConfiguration` 按执行模式提供 resolver：

- `PTY`：使用配置的 Bash 和本轮工作区路径创建 `PtyTarget`。
- `DOCKER`：确认本轮路径位于 `agent.production.workspace` 根目录内，再计算相对路径；
  Docker Engine bind source 使用配置 mount 根加该相对路径，一次性容器内部仍固定挂载到
  `agent.production.container-workspace`。

`DockerTarget.ContainerWorkspaceSource` 增加精确 `relativePath` 字段，二参数构造器保持
`relativePath=""`。`DockerCommandExecutor` 只接受规范化的相对路径，拒绝绝对路径和
`..`，并在解析宿主 bind root 后追加该相对路径。

### 里程碑二：本机 CLI

新增 Maven 模块 `agent-cli`，只作为 Agent4J 产品客户端，不承载第二套图引擎。入口命令：

```text
agent4j chat --workspace <path> --server <uri>
agent4j serve --workspace <path> --compose-file <path>
agent4j conversations --server <uri>
```

精确默认值：

- `chat --workspace` 默认当前目录。
- `--server` 默认 `http://localhost:8080`。
- `serve --compose-file` 默认仓库根目录的 `docker-compose.local.yml`。

在 Windows 本机存在多个 8080 监听者时，CLI 对精确的 `localhost` 默认值按
`[::1] -> 127.0.0.1` 顺序探测，并在身份请求成功后固定实际端点；readiness
必须返回 JSON `status=UP`，不能仅凭 HTTP 200 判定服务可用。

`serve` 校验工作区是真实目录，然后以进程环境变量
`AGENT_CODE_HOST_WORKSPACE=<绝对路径>` 调用现有 Compose 命令。它不修改 `.env`，也不输出
任何环境变量值。服务 healthy 后输出 Web URL。

`chat` 使用 Java 21 `HttpClient` 调用现有 API：解析身份，查找与服务端当前挂载根对应的
工作区，创建或恢复会话，提交轮次，按轮次的 `runId` 读取 SSE Trace 和终端日志，并轮询
轮次终态。交互循环支持 `/new`、`/sessions`、`/use <conversationId>`、`/status`、`/exit`。
所有标识符和 JSON 字段使用精确匹配，服务端错误正文原样显示但经过 CLI 的 ANSI 安全输出。

CLI 产物使用 Maven Shade Plugin 生成可执行 JAR，并提供根目录 `agent4j.ps1` 与
`agent4j.sh` 启动脚本。

### 里程碑三：Web 工作区入口与执行可见性

新增 `WorkspaceDialog`，入口位于侧栏工作区选择器旁的文件夹加号图标。Dialog 只提交现有
`POST /api/workspaces` 的三个精确字段：

```text
displayName
workspacePath
repositoryId
```

界面展示服务端返回的 `workspacePath` 与 `permission`，并明确目录必须位于当前 Agent
挂载根内。创建成功后立即切换工作区并允许创建首个会话。重复路径返回的服务端错误显示在
Dialog 内，不把 500 堆栈暴露给用户。

`useConversationWorkspace` 增加 `createWorkspace`，并将活动 `workspaceId` 与
`conversationId` 同时持久化到 URL。页面加载时只读取所请求工作区下的会话，避免当前实现
为每个工作区串行加载会话。

对话区继续作为主操作面；执行检查器保持四个 Tab：代码变更、终端、浏览器、Trace。
运行中的 `NODE_PROGRESS`、模型调用摘要、工具调用、PTY 命令、最终回答和失败原因均保留在
当前轮次旁，用户不需要打开日志文件才能知道 Agent 正在做什么。

## 数据流

1. 用户在项目目录执行 `agent4j serve --workspace .`。
2. CLI 将真实目录作为 `AGENT_CODE_HOST_WORKSPACE` 启动 Compose；应用内挂载根为
   `/agent-workspace`。
3. CLI 或 Web 创建/选择工作区和会话，提交用户输入。
4. `ConversationService` 将精确工作区路径写入 `coder.workspacePath`。
5. Planner 决定快速问答、知识回答、代码链或 GUI 链。
6. 代码链中的 Coder、CLI 授权与 Ops 从同一工作区路径解析资源。
7. Run Trace、终端日志和 Checkpoint 通过现有协议推送；终态投影写回会话轮次。
8. CLI 和 Web 从 PostgreSQL 支持的 REST API 恢复会话，不维护第二份本地历史。

## 错误与安全

- 工作区路径必须先 `toRealPath()`，并位于配置根目录内。
- Docker 相对路径必须规范化，拒绝绝对路径、`..` 和符号链接越界。
- CLI 不打印 `.env`、API Key、数据库密码或 Authorization。
- Web 不声称能够访问浏览器选择但服务端未挂载的目录。
- 同一会话仍禁止并发活动轮次，冲突返回 HTTP 409。
- CLI 收到 409、404、422 或 500 时显示 HTTP 状态和 ProblemDetail，不自行改写字段。
- 终端命令继续经过 `CliCommandCatalog`、HITL 和一次性 Docker 容器。

## 测试

### 工作区执行

- `CliApprovalInterruptPolicyTest` 验证两个状态工作区解析为两个不同目标。
- `DockerCommandExecutorTest` 验证子目录 bind、空相对路径、绝对路径和 `..` 拒绝。
- `ProductionGraphConfigurationTest` 验证 PTY 与 Docker resolver 使用本轮路径。
- 集成测试创建两个子工作区，确认命令输出各自目录中的不同文件内容。

### CLI

- 使用本地 HTTP Stub 验证精确 URL、请求 JSON、错误映射和终态轮询。
- 使用临时目录与伪 Compose 进程验证 `serve` 环境变量和退出码。
- 使用脚本输入验证 `/new`、`/sessions`、`/use`、普通消息和 `/exit`。

### Web

- Vitest 覆盖工作区创建 API 解码、Hook 状态和 Dialog 错误。
- React Testing Library 覆盖工作区创建、切换、会话创建和发送消息。
- Java 集成测试覆盖 `POST /api/workspaces` 后的工作区与会话闭环。
- 浏览器测试覆盖桌面和移动布局、URL 恢复、Diff、ANSI 终端、Trace 与审批。

## 提交边界

1. `fix(workspace): bind terminal execution to each run workspace`
2. `feat(cli): add interactive Agent4J client`
3. `feat(web): add workspace import and persistent selection`
4. `docs(product): document CLI and workspace workflow`

每个提交均先运行对应红灯测试，再做最小实现和模块级验证；最终运行 JDK 21
`mvn clean verify`、前端测试、Docker Compose 黑盒会话和 CLI 真实进程验收。
