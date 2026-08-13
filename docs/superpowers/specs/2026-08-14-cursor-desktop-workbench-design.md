# Cursor 风格桌面工作台设计

## 目标

为 Agent4J 提供 Windows 优先的桌面外壳和适合代码 Agent 的高密度工作台。桌面端保留现有 Spring Boot、Docker、MCP、Skill、模型路由、会话、审批、终端、Diff 和 Trace 能力，不复制 Cursor 的品牌、资产或私有实现。

## 决策

采用独立的 `agent-desktop/` Electron 应用。

当前开发机不存在 `cargo` 和 `rustc`，而 Web 工作台已经采用 React、Vite 和 Node。因此 Electron 可以复用现有前端技术栈及 Windows 原生目录选择，而不把 Rust 安装与 Tauri 工具链作为项目运行前提。

Electron 不进入 `agent-web` 的 Maven 静态资源构建。Web JAR、Docker Compose 服务和桌面安装包独立发布；桌面端默认连接本地 `http://127.0.0.1:8080`。

## 架构

```text
Electron main process
  - 本地 readiness 探测
  - 原生目录选择
  - 受限目录归档
  - 受限 BrowserWindow 生命周期
        |
        v
Electron preload context bridge
  - selectProjectArchive()
        |
        v
Agent4J React workbench
  - 上传 ZIP 至 POST /api/workspace-imports
  - 对话、运行、审批、终端、Diff、Trace、MCP、Skill
        |
        v
Spring Boot / Docker runtime
  - 现有 WorkspaceImportService
  - 现有 WorkspaceAccessService
  - 现有 ActorResolver
```

桌面主进程使用 `dialog.showOpenDialog` 选择目录，在主进程内递归读取普通文件并生成 ZIP。渲染进程只能收到归档字节、文件数、字节数与建议显示名称，永不收到宿主绝对路径。

目录归档拒绝符号链接、junction、设备文件、绝对归档条目和包含 `..` 的条目。服务端继续是导入路径和解压限制的权威实现，桌面端不新增按主机路径注册工作区的 API。

## 安全边界

`BrowserWindow` 固定：

- `contextIsolation: true`
- `sandbox: true`
- `nodeIntegration: false`
- 禁止任意 `window.open`
- 仅允许已探测成功的本机 `http://127.0.0.1:8080` origin 导航

preload 仅提供以下窄接口：

```ts
interface Agent4jDesktopBridge {
  selectProjectArchive(): Promise<{
    archive: Uint8Array
    fileCount: number
    totalBytes: number
    suggestedDisplayName: string
  } | null>
}
```

不暴露 `ipcRenderer`、`process`、Node 文件系统、子进程或通用 IPC。

## 连接与身份

启动屏只在 readiness 返回 HTTP `200` 且 JSON 的 `status` 精确为 `UP` 时加载工作台。服务离线时显示连接状态、重试与本地 Compose 启动命令，不显示可操作的 Agent 页面。

工作台显示既有 `/api/identity` 的显示名与用户 ID，并标记为“本地身份”。提供账户菜单挂载点，但本期不实现登录、退出、同步或前端修改身份。后续认证仅替换服务端 `ActorResolver` 的来源。

## 工作台信息架构

采用 Cursor 风格的 IDE 信息密度：

```text
活动栏 | 项目与会话栏 | 中央 Agent 对话 / 任务 | 检查器
       |              |                        | Code / Terminal /
       |              |                        | Browser / Trace / Capability
       |              |------------------------|
       |              | 底部终端可展开          |
```

- 活动栏切换“对话、项目、运行证据、能力”。
- 项目栏显示工作区、会话、连接状态与身份入口。
- 中央区继续使用现有 Agent 消息、Markdown、模型选择与 `/` 受治理命令。
- 右侧检查器复用现有 Diff、Terminal、Review、Trace、Capability 页面，不重写已有运行链。
- 小屏下将检查器收纳为抽屉或底部面板，保持文字可读且不重叠。

视觉采用深石墨背景、低饱和石板层级、绿色表示运行成功、蓝色表示当前选择、琥珀色表示审批，避免复制 Cursor 的图标、文案或资产。

## 验收

1. Electron 可探测本机服务的 readiness，并在离线时保持不可操作。
2. 用户能使用 Windows 原生目录选择器导入项目；网络请求只包含 ZIP，不包含宿主绝对路径。
3. 导入结果仍落在服务端 `/agent-workspace/.agent4j/imports/...` 受控目录。
4. 既有聊天、MCP/Skill、审批、Terminal、Diff、Trace 和模型组能力在桌面壳中可用。
5. 工作台满足 Cursor 风格的信息层级与响应式布局，不引入虚假的文件编辑或账号行为。
6. Electron 单元测试、React 全量测试、Electron build 和桌面打包通过；真实 Docker/模型验收保持可运行。
