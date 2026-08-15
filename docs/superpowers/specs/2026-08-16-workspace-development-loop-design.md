# 工作区开发闭环设计

## 目标

让用户从 Agent4J 工作台创建空项目或导入外部项目后，能够在同一个工作区内浏览文件树、读取和编辑文本文件、提交受治理的命令与测试，并在对话、Trace 和终端面板中看到完整证据。所有文件操作都必须被限定在已授权工作区目录内，不能通过路径穿越、符号链接或绝对路径访问宿主机其他位置。

## 当前边界

- `WorkspaceAccessService` 只允许注册配置根目录下已经存在的目录。
- `WorkspaceImportService` 已支持 ZIP 导入，但没有项目文件树和文件内容 REST API。
- `RunController` 已把 `workspacePath` 注入生产 Code Agent，受治理 CLI 已使用同一个 `WorkspaceRecord`。
- 前端 `Workbench` 的项目活动栏仍渲染 `ConversationSidebar`，`CodeDiffPanel` 只展示运行产生的 unified diff。

本设计不引入新的 Agent 执行器，不绕过现有 `WorkspaceAccessService`、`AgentRunService`、受治理 CLI、Trace 或终端 SSE。

## 方案

### 1. 项目生命周期

增加 `POST /api/workspaces/projects`，请求包含 `displayName`、`repositoryId` 和一个不含路径分隔符的 `directoryName`。服务在 `agent.workspace.root` 下创建目录，再调用既有 `WorkspaceAccessService.create` 注册工作区。目录创建使用 `CREATE_NEW` 语义并在重名时返回冲突，不覆盖已有项目。

现有 `POST /api/workspace-imports` 继续负责 ZIP 导入；导入完成后返回的 `WorkspaceView` 直接进入同一套文件浏览器。宿主机绝对路径只在服务端配置中存在，浏览器只看到工作区 ID 和相对文件路径。

### 2. 文件资源 API

新增 `WorkspaceFileService`，所有入口先调用 `WorkspaceAccessService.requireWorkspace`，再把请求中的相对路径解析为工作区根下的真实路径。以下路径格式是公开契约：

- `GET /api/workspaces/{workspaceId}/files?path=`：返回目录项数组。`path` 为空表示工作区根；目录项包含 `name`、`path`、`kind`、`size`、`lastModified`。
- `GET /api/workspaces/{workspaceId}/files/content?path=`：读取 UTF-8 文本并返回 `path`、`content`、`sha256`、`lastModified`。
- `PUT /api/workspaces/{workspaceId}/files/content`：请求包含 `path`、`content`、`expectedSha256`。服务校验父目录存在、目标不是目录、内容为 UTF-8，并使用临时文件加原子替换写入；`expectedSha256` 不匹配返回冲突，避免覆盖外部修改。

默认只返回工作区内的直接子项；服务拒绝 `..`、绝对路径、符号链接和工作区外解析结果。单文件读取和写入上限由 `agent.workspace-files.max-file-bytes` 控制，目录列表限制条目数量，二进制文件返回明确的不可编辑错误。

### 3. 前端工作台

新增 `WorkspaceExplorerPanel`，固定在项目活动栏对应的左侧列。它包含：工作区名称栏、刷新按钮、可键盘操作的树形列表、当前文件编辑器和保存状态。树列表只滚动中部，顶部工作区栏固定；编辑器使用 Monaco 的单文件文本模式，保存按钮和 `Ctrl+S` 都调用内容 PUT 并携带当前 SHA-256。冲突时保留本地编辑内容并提示重新加载或覆盖，不静默丢失修改。

对话 Composer 保持原位置；“运行 Agent”与受治理 CLI 仍由现有链路处理。文件面板中的“运行测试”只提交已有 CLI 目录中的命令，结果进入现有终端/Trace 检查器，不新增自由 shell 接口。

### 4. 审计与错误

文件创建、目录列表、内容读取、内容写入和冲突都写入现有会话审计 sink，记录用户、工作区、相对路径、字节数、SHA-256 和结果，不记录文件正文。错误映射保持现有 REST 错误格式：无权限返回 403，路径不存在返回 404，二进制或过大文件返回 422，SHA 冲突返回 409。

## 测试与验收

后端单元测试覆盖：空项目创建、目录树排序、路径穿越、符号链接、工作区外路径、UTF-8 读写、SHA 冲突、文件大小限制和权限门禁。Controller 测试验证公开 JSON 字段和 HTTP 状态码。

前端测试覆盖：项目活动栏切换、树键盘导航、目录展开、文本加载、保存成功、保存冲突和加载错误。真实验收夹具从空目录创建一个最小 Java 项目，使用文件 API 写入源码和测试，再通过受治理 Maven 命令执行测试，最后从 `/api/runs/{runId}/events` 和 `/api/runs/{runId}/logs` 读取 Trace/终端证据。

## 分阶段交付

1. 后端项目创建与文件资源 API、单元/Controller 测试。
2. 前端项目资源面板、Monaco 文本编辑和键盘交互测试。
3. 真实空项目 EDD，串联文件写入、Agent 对话、受治理测试、Trace 和终端证据。
