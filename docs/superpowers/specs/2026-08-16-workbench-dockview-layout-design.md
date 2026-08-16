# Agent4J Workbench Dockview Layout Design

## 目标

将工作台改为固定外壳、独立内容滚动和可恢复的 Dockview 面板布局。用户可以拖动、拆分、合并、隐藏和恢复活动栏、项目/会话栏、对话区及执行检查器；主题强调色必须由 `--accent` 统一驱动。

## 布局模型

- 顶部品牌栏固定，不参与内容滚动。
- Dockview 承载四个可停靠面板：活动栏、项目/会话、对话、执行检查器。
- 初始布局保持现有 VS Code 风格：活动栏窄列、项目/会话左栏、对话中心、检查器右栏。
- 面板关闭后不销毁业务状态；布局重载时按面板 ID 恢复。
- `dockview.layout.v1` 使用 `localStorage` 持久化，解析失败或版本不匹配时回退默认布局。
- 面板标题、关闭按钮和恢复默认布局入口提供可访问名称；Dockview 的拖拽/分割由库负责。

## 滚动边界

- 页面、工作台外壳和 Dockview 根节点禁止滚动。
- 每个面板使用 `display:grid` 或 `display:flex` 的 `min-height:0` 结构。
- 只有会话列表、对话消息、文件树、检查器内容和对话框内容滚动。
- 搜索/筛选工具栏与归档/删除操作固定在会话栏底部；检查器标题和标签栏固定，当前面板内容独立滚动。
- 所有滚动条使用同一 `scrollbar-color`、`scrollbar-width` 和 `overscroll-behavior: contain` 规则。

## 主题响应

- 活动态、焦点环、选中标签、Dockview drop target 和主操作按钮均使用 `--accent` 及 `color-mix(in srgb, var(--accent) ...)`。
- 禁止在组件样式中写死蓝色 rgba 值作为选中态。
- Dockview 自定义 CSS 变量映射当前 surface、border、text 和 accent；外观设置变化后实时更新。

## 验证

- Vitest：面板注册、默认布局、布局序列化/恢复、面板关闭和主题变量映射。
- 生产构建：`npm run build`。
- Playwright/浏览器截图：宽屏、1280px、900px、760px 和移动宽度；确认只有中部内容滚动，底部操作固定，选中态随自定义强调色变化。
