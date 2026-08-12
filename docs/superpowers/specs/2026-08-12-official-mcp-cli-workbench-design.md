# Agent4J 官方 MCP 清单与受治理 CLI 工作台设计

## 目标

为 Agent4J 增加两条可审计的能力入口：

1. 从官方 `modelcontextprotocol/servers` GitHub 仓库读取真实目录和版本元数据，按工作区执行检索、预览、确认安装，并在隔离运行环境中通过 MCP stdio 接入工具注册表。
2. 在聊天输入框输入 `/` 时展示当前工作区受治理 CLI 命令目录，结构化填写参数，提交前展示风险和审批状态，批准后复用现有 Run、Terminal、Trace 执行链。

现有配置驱动的 HTTP/HTTPS MCP 连接继续保留，两种传输统一进入 `ToolRegistry`，不允许任意 Shell 文本或未审批的远程内容进入执行链。

## 边界与安全策略

- 官方 MCP 发现源固定为 `https://api.github.com/repos/modelcontextprotocol/servers/contents` 及对应 raw 文件地址；仓库当前是参考实现集合，不视为生产服务目录。
- 发现结果必须保存仓库路径、文件 SHA、版本、许可证和来源 URL。解析不到启动配置时只能生成安装预览，不得伪称已启用。
- 安装目标分为工作区和用户全局。默认工作区安装绑定 `workspaceId` 与 `actor.userId`；全局安装必须由用户显式选择。
- 安装前展示来源、版本/SHA、内容摘要、启动命令、环境变量名、风险级别和所需能力；用户确认后才持久化并启动。
- stdio 服务只能由受治理的 MCP 运行器启动，命令和参数来自已解析且校验过的官方元数据，工作目录位于工作区隔离根目录。禁止把用户输入拼接为可执行命令。
- MCP 工具仍由现有 `ToolRegistry`、能力掩码和审批策略控制。远程工具失败必须返回结构化错误并记录 Trace/Audit。
- CLI 仅暴露 `CliCommandCatalog` 中的精确命令。前端提交结构化命令名和参数，不接受任意 shell 字符串。

## 组件设计

### 官方 MCP 目录

新增官方目录客户端，使用 Java 21 虚拟线程执行 GitHub HTTP 请求，具有超时、响应大小、缓存 TTL 和 GitHub 失败审计。客户端读取根目录、`src/` 服务目录、服务 README、`package.json` 或 `pyproject.toml`，按实际字段生成不可变服务记录。解析规则严格限定到已读取的字段：TypeScript 服务读取 `name/version/bin`，Python 服务读取 `project.name/project.version` 和 README 中的 `uvx`/`python -m` 示例。

### MCP 安装与运行

新增安装预览、确认和卸载 API。安装记录包含安装范围、工作区/用户绑定、来源 commit SHA、包管理器、命令参数、环境变量名、状态和审计时间。确认后由工作区运行器在隔离目录创建配置快照并启动 stdio 进程；实现 `McpTransport` 的 stdio 适配器，复用 `McpClient`、`McpToolRegistryAdapter` 完成握手和工具发现。进程退出、协议错误、超时均关闭客户端并将状态置为失败。

### CLI 工作台

新增只读命令目录 API，返回命令名、描述、参数定义、风险级别、所需能力和是否需要审批。前端 composer 在输入 `/` 或 `/` 后缀查询时展示命令列表；选择命令后渲染参数输入，提交前显示授权决定。后端新增结构化 CLI Run 创建接口，调用 `CliCommandCatalog.authorize`，READ_ONLY 直接执行，MUTATING/DESTRUCTIVE 创建待审批 Run；批准后进入既有 `GovernedCliCommandExecutor`、SSE 日志和 WebSocket Terminal。

## 数据持久化

新增 Flyway migration 保存：

- 官方 MCP 目录缓存及文件 SHA；
- MCP 安装记录（工作区/全局范围、配置快照、状态、版本）；
- 安装审批和卸载审计事件。

所有表使用现有 UTC `timestamptz` 存储，由应用层以北京时间展示；敏感环境变量只保存名称，不保存值。

## API 与前端验收

- `GET /api/mcp/catalog`：官方缓存服务列表与刷新状态。
- `GET /api/workspaces/{workspaceId}/mcp/installations`：当前用户可见安装。
- `POST /api/workspaces/{workspaceId}/mcp/installations/preview`：生成安装预览，不产生副作用。
- `POST /api/workspaces/{workspaceId}/mcp/installations`：携带确认标记创建安装并启动。
- `DELETE /api/workspaces/{workspaceId}/mcp/installations/{installationId}`：停止并卸载。
- `GET /api/workspaces/{workspaceId}/cli/commands`：受治理命令目录。
- `POST /api/workspaces/{workspaceId}/cli/runs`：提交结构化命令请求。

前端显示来源和风险，不显示原始 Markdown/JSON 配置作为唯一界面；详情区域预留可扩展的代码块、表格和工具列表渲染。

## 测试与可观测性

- 目录客户端测试真实 JSON 字段校验、SHA 变化、缓存过期、GitHub 失败和未知字段拒绝。
- 安装测试覆盖工作区边界、全局显式选择、审批前无副作用、stdio 握手/分页/退出和 ToolRegistry 能力门禁。
- CLI 测试覆盖命令目录过滤、参数校验、审批决定和 Run/日志关联。
- 前端测试覆盖 `/` 键盘选择、参数校验、风险预览、失败提示和模型选择共存。
- 每次目录刷新、预览、确认、启动、调用、停止、CLI 提交和审批均写入现有审计/Trace，记录 actor、workspace、来源 SHA、runId 和结果；不记录密钥值。

## 分期

1. 官方目录客户端、缓存表和只读目录 API。
2. 安装预览/确认持久化与工作区隔离 stdio 运行器。
3. stdio MCP 接入现有 `McpClient` 和 ToolRegistry，补齐审计。
4. CLI 目录/结构化 Run API 与聊天 `/` 面板。
5. 集成测试、前端验收和真实 MCP reference server 冒烟验证。
