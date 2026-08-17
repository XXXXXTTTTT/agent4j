# Workbench Editor Preview and Live Resize Design

## Goal

将项目文件树与中心编辑器分离，并保证 Dockview 在拖拽、停靠、窗口调整和面板开关后立即重新布局。

## Architecture

工作台保留活动栏、项目/会话侧栏、对话和执行详情四类现有 Dockview 面板，新增 `editor` 中心面板。项目树只负责目录导航，通过工作台上下文调用 `openFile(path)`；编辑器面板维护打开文件标签、当前文件、未保存状态和保存/关闭动作。Dockview 外壳使用 `ResizeObserver` 读取自身内容区尺寸，调用 `api.layout(width, height)`，并在布局变更和面板可见性变化后调用 `api.forceResize()`，让 Monaco、xterm 和滚动容器获得最新尺寸。

## File Preview Contract

- 点击文本文件在中心编辑器打开或激活对应标签。
- 标签标题使用文件名，重复打开只激活现有标签。
- 编辑内容变化后标签显示未保存标记。
- 单个关闭和“全部关闭”都经过统一关闭策略；有未保存文件时提供保存、放弃、取消。
- 保存使用现有 `readWorkspaceFile`/`writeWorkspaceFile` API 和 SHA 乐观并发校验。
- 二进制或读取失败文件继续显示在侧栏错误状态，不创建编辑器标签。

## Layout Contract

- 移除 `disableAutoResizing`，并增加 `ResizeObserver` 作为显式兜底。
- 观察 `.workbench-dockview` 的 `contentRect`，在 `requestAnimationFrame` 中合并连续变化，尺寸为正时调用 `api.layout(width, height)`。
- Dockview 布局完成后，对当前编辑器面板调用 Monaco 的布局刷新入口；xterm 继续由自身观察器适配。
- 所有面板容器使用 `min-width: 0; min-height: 0; overflow: hidden`，内部滚动区域独立滚动。
- 持久化布局白名单加入 `editor`，旧布局加载时仍可安全回退默认布局。

## Testing

- Dockview persistence tests cover the new editor panel id and old-layout fallback.
- Explorer tests verify a file-open callback is invoked without rendering an editor inside the sidebar.
- Editor tests cover opening, activation, dirty state, save, single close and close-all confirmation.
- Layout tests provide a fake `ResizeObserver` and verify `api.layout` receives changed dimensions without a page refresh.
- Run focused Vitest, full frontend Vitest and production Vite build before Docker rebuild.
