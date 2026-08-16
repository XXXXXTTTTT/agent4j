# Workbench Dockview Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有工作台迁移为固定顶部外壳与 Dockview 四面板布局，限制滚动边界，统一 `--accent` 主题响应，并使用 `dockview.layout.v1` 恢复用户布局。

**Architecture:** 保留 `useRunWorkbench` 与 `useConversationWorkspace` 作为业务状态唯一来源，在 `Workbench` 外层引入 Dockview 容器。四个稳定面板 ID（`workbench.activity`、`workbench.projects`、`workbench.conversation`、`workbench.inspector`）映射现有活动栏、会话/项目栏、对话区和执行检查器；Dockview 只负责停靠、拆分、关闭和恢复，面板内容继续复用现有组件。布局 JSON 通过独立纯函数做版本校验后写入 `localStorage`，异常数据回退默认布局。

**Tech Stack:** React 19、TypeScript、`dockview-react@8.1.0`、原生 CSS 自定义属性、Vitest、Testing Library、Spring Boot 集成的 Playwright Java 浏览器验收。

---

### Task 1: 布局序列化与版本回退

**Files:**
- Create: `agent-web/src/main/frontend/src/workbench/layoutPersistence.ts`
- Create: `agent-web/src/main/frontend/src/workbench/layoutPersistence.test.ts`

- [ ] **Step 1: 编写失败测试，锁定存储协议**

测试固定常量 `DOCKVIEW_LAYOUT_STORAGE_KEY = 'dockview.layout.v1'`；`readLayout` 在合法 Dockview JSON 上返回同一对象，在无值、JSON 解析失败、缺少 `grid` 或 `panels` 时返回 `null`；`writeLayout` 调用 `localStorage.setItem`，序列化失败时不向外抛出异常。测试使用 `localStorage.clear()`，不依赖浏览器布局。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm run test:run -- layoutPersistence.test.ts --maxWorkers=1`

Expected: FAIL，因为 `layoutPersistence.ts` 尚不存在。

- [ ] **Step 3: 实现纯函数和精确类型边界**

从 `dockview` 导入 `SerializedDockview`，实现以下契约；不在该模块创建 Dockview 实例：

```ts
export const DOCKVIEW_LAYOUT_STORAGE_KEY = 'dockview.layout.v1'
export function readLayout(storage: Storage = window.localStorage): SerializedDockview | null
export function writeLayout(layout: SerializedDockview, storage: Storage = window.localStorage): void
export function clearLayout(storage: Storage = window.localStorage): void
```

`readLayout` 必须检查解析结果为对象且 `grid`、`panels` 均存在，再交给 `DockviewApi.fromJSON`；任何 `Storage` 异常、解析异常或结构缺失均返回 `null`。`writeLayout` 只写入 `JSON.stringify(layout)`，失败时吞掉存储异常并保持页面可用。

- [ ] **Step 4: 运行测试确认通过**

Run: `npm run test:run -- layoutPersistence.test.ts --maxWorkers=1`

Expected: PASS。

### Task 2: Dockview 容器与四个稳定面板

**Files:**
- Create: `agent-web/src/main/frontend/src/workbench/WorkbenchDockview.tsx`
- Create: `agent-web/src/main/frontend/src/workbench/workbenchPanels.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.test.tsx`

- [ ] **Step 1: 编写面板注册和默认布局测试**

在 `WorkbenchDockview.test.tsx` 中用 `DockviewReact` 的 `onReady` 事件注入测试 API，断言 `api.addPanel` 注册四个精确 ID、标题分别为“活动栏”“项目与会话”“对话”“执行检查器”，且默认布局包含活动栏窄列、项目/会话左列、对话中心、检查器右列。断言 `components` 映射能渲染现有组件，不直接调用业务 API。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm run test:run -- WorkbenchDockview.test.tsx --maxWorkers=1`

Expected: FAIL，因为 Dockview 容器和面板注册尚不存在。

- [ ] **Step 3: 实现容器和默认布局**

使用已验证的 `DockviewReact`、`DockviewReadyEvent`、`DockviewApi`、`IDockviewPanelProps` 类型。`onReady({ api })` 中先调用 `readLayout()`；有合法布局时调用 `api.fromJSON(layout, { reuseExistingPanels: true })`，否则按固定顺序调用 `api.addPanel`，使用 `position.direction` 建立 `left`/`right` 关系和 `initialWidth`（48、248、380），对话面板置于中心。为四个面板设置 `renderer: 'always'`，使面板被切换或折叠时不销毁内容节点；关闭面板只移除 Dockview 壳，业务数据仍由 `Workbench` 的 controller/conversation 保存。

面板实现放在 `workbenchPanels.tsx`：活动栏渲染 `ACTIVITY_ITEMS`，项目与会话面板按 `activeActivity` 选择 `WorkspaceExplorerPanel` 或 `ConversationSidebar`，对话面板渲染 `AgentConversation`、`ApprovalDialog` 和 `ConversationComposer`，检查器面板渲染现有五个 tab 视图。面板 props 通过 `IDockviewPanelProps.params` 接收 `controller`、`conversation` 和 `onTerminalReady`，不复制 hook 状态。

- [ ] **Step 4: 接入 `Workbench` 并保留可访问契约**

将现有 `.agent-layout` 四列 DOM 替换为 `<WorkbenchDockview ... />`，保留 `.workbench-shell`、`.workbench-header`、`data-testid="workspace-main"`、`aria-label="执行检查器"` 和 `run-launcher`。顶部品牌栏仍在 Dockview 外部固定。增加“恢复默认布局”按钮，调用 `api.clear()` 后按默认布局重新 `addPanel`，并设置 `aria-label="恢复默认布局"`。

- [ ] **Step 5: 运行回归测试**

Run: `npm run test:run -- WorkbenchDockview.test.tsx Workbench.test.tsx --maxWorkers=1`

Expected: PASS，原有对话、审批、Trace、终端和浏览器证据断言继续通过。

### Task 3: 自动保存、关闭恢复与主题映射

**Files:**
- Modify: `agent-web/src/main/frontend/src/workbench/WorkbenchDockview.tsx`
- Create: `agent-web/src/main/frontend/src/workbench/dockviewTheme.ts`
- Create: `agent-web/src/main/frontend/src/workbench/dockviewTheme.test.ts`
- Modify: `agent-web/src/main/frontend/src/styles.css`

- [ ] **Step 1: 编写持久化与主题测试**

测试 `onReady` 后订阅 `api.onDidLayoutChange`，每次事件调用 `writeLayout(api.toJSON())`；重新挂载时从 `dockview.layout.v1` 调用 `fromJSON`；存储内容版本或结构错误时调用默认布局。主题测试读取 `getDockviewTheme` 返回的 `surface`、`border`、`text`、`accent` 映射，断言值来自 CSS 变量而非固定蓝色。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm run test:run -- WorkbenchDockview.test.tsx dockviewTheme.test.ts --maxWorkers=1`

Expected: FAIL，因为自动保存和 Dockview 主题映射尚不存在。

- [ ] **Step 3: 实现布局监听与恢复入口**

在 `onReady` 中对 `api.onDidLayoutChange` 注册一次性清理函数；事件处理器执行 `writeLayout(api.toJSON())`。恢复默认布局先 `api.clear()`，再按默认面板顺序重新添加；不要删除 `controller`、`conversation` 或终端句柄。`readLayout` 返回 `null` 时记录一次 `console.warn`，并继续默认布局，不能阻断渲染。

- [ ] **Step 4: 实现主题变量映射**

`getDockviewTheme()` 读取 `document.documentElement` 的计算样式，返回 Dockview 可接受的主题对象：`surface` 对应 `--surface-raised`，`border` 对应 `--border-subtle`，`text` 对应 `--text-primary`，`accent` 对应 `--accent`。在 `AppearanceProvider` 的主题属性变化后更新 Dockview 容器 class/变量，使切换模式、预设或自定义强调色实时生效。

- [ ] **Step 5: 运行测试确认通过**

Run: `npm run test:run -- WorkbenchDockview.test.tsx dockviewTheme.test.ts --maxWorkers=1`

Expected: PASS。

### Task 4: 滚动边界与响应式样式

**Files:**
- Modify: `agent-web/src/main/frontend/src/styles.css`
- Modify: `agent-web/src/main/frontend/src/components/ConversationSidebar.tsx`
- Modify: `agent-web/src/main/frontend/src/components/WorkspaceExplorerPanel.tsx`
- Modify: `agent-web/src/main/frontend/src/components/CapabilityWorkbenchPanel.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Modify: `agent-web/src/main/frontend/src/styles.test.ts`

- [ ] **Step 1: 编写 CSS 契约测试**

断言 `html, body, #root`、`.workbench-shell`、`.workbench-dockview` 的 `overflow: hidden`；每个 Dockview 面板根节点含 `min-height: 0`；会话列表、对话消息、文件树、检查器内容和对话框内容含 `overflow: auto` 与 `overscroll-behavior: contain`。断言 `.conversation-sidebar` 使用 `grid-template-rows: auto auto minmax(0,1fr) auto`，底部归档/删除区域存在且不在滚动列表内。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm run test:run -- styles.test.ts --maxWorkers=1`

Expected: FAIL，直到新增 Dockview 根节点和完整滚动契约。

- [ ] **Step 3: 实现固定外壳与独立滚动**

为 Dockview 根设置 `display: flex; min-height: 0; overflow: hidden`；面板内容统一 `display: grid` 或 `display: flex` 并设置 `min-height: 0`。仅以下容器允许滚动：`.conversation-list`、`.conversation-scroll`、`.workspace-explorer-tree`、`.workspace-views > [role="tabpanel"]`、`.capability-scroll`、对话框内容容器。检查器标题 `.inspector-heading` 与 `.workspace-tabs` 保持固定，`.workspace-views` 独立滚动。所有滚动区域统一 `scrollbar-color: var(--scrollbar-thumb) transparent`、`scrollbar-width: thin`、`overscroll-behavior: contain` 及同一 WebKit thumb 规则。

- [ ] **Step 4: 移除硬编码选中蓝色并接入 `--accent`**

将活动态、焦点环、选中标签、Dockview drop target、主操作按钮改为 `var(--accent)` 或 `color-mix(in srgb, var(--accent) ...)`。删除活动栏和会话项中的 `rgba(138, 180, 255, ...)`；新增 `--scrollbar-thumb` 语义变量，并让 Dockview surface/border/text 变量随 `data-color-mode`、`data-theme-preset` 和自定义 `--accent` 更新。

- [ ] **Step 5: 运行样式与组件回归测试**

Run: `npm run test:run -- styles.test.ts themedControls.test.ts ConversationSidebar.test.tsx WorkspaceExplorerPanel.test.tsx CapabilityWorkbenchPanel.test.tsx --maxWorkers=1`

Expected: PASS。

### Task 5: Dockview 行为、可访问性与持久化集成测试

**Files:**
- Modify: `agent-web/src/main/frontend/src/workbench/WorkbenchDockview.test.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.test.tsx`
- Modify: `agent-web/src/main/frontend/src/test/setup.ts` (仅在测试需要时提供 `ResizeObserver`/`matchMedia` stub)

- [ ] **Step 1: 覆盖关闭/恢复和状态保留**

测试关闭 `workbench.inspector` 后其 DOM 不可见、对话 controller 引用和会话消息仍存在；点击“恢复默认布局”后四个面板重新出现。模拟 `api.toJSON()` 写入后卸载并重新挂载，断言 `fromJSON` 收到同一布局对象；向 `localStorage` 写入无效 JSON，断言默认布局恢复而不是抛异常。

- [ ] **Step 2: 覆盖无会话和窄屏分支**

渲染 `conversation` 未提供的空闲工作台，断言 `run-launcher`、`workspace-main` 和检查器仍有可访问名称；设置 `matchMedia('(max-width: 1280px)')` 为匹配，断言检查器可通过 Dockview 关闭/恢复，不产生页面级横向滚动。

- [ ] **Step 3: 运行完整 Vitest 套件**

Run: `npm run test:run -- --maxWorkers=1`

Expected: PASS，且无未处理的 React、Dockview 或 Monaco 警告。

### Task 6: 生产构建与 Playwright 截图验收

**Files:**
- Modify: `agent-web/src/test/java/com/agent/web/ProductWorkbenchBrowserTest.java`
- Modify: `agent-web/src/test/resources/workbench/desktop-reference.json`
- Modify: `agent-web/src/test/resources/workbench/mobile-reference.json`

- [ ] **Step 1: 扩展浏览器验收断言**

在现有 `ProductWorkbenchBrowserTest` 中增加 2048×1017、1280×900、900×900、760×844 与移动宽度视口。对每个视口断言 `document.documentElement.scrollWidth === viewport.width`、顶部品牌栏和 Dockview 根高度固定；滚动 `.conversation-scroll`、`.conversation-list`、`.workspace-views > [role="tabpanel"]` 后页面 `scrollTop` 保持 0，底部归档/删除操作仍可见。拖动或关闭检查器后刷新页面，断言 `dockview.layout.v1` 恢复相同面板 ID 与分割关系。切换外观预设/自定义强调色，读取 `getComputedStyle(document.documentElement).getPropertyValue('--accent')`，并断言活动态边框和主按钮颜色与该值一致。每个视口保存非空 PNG 到 `target/workbench/`。

- [ ] **Step 2: 运行前端构建**

Run: `npm run build`

Expected: Vite 构建退出码为 0，静态产物写入 `target/classes/static`。

- [ ] **Step 3: 运行 Java 模块测试与浏览器验收**

Run: `mvn -pl agent-web -am test -Dtest=ProductWorkbenchBrowserTest`

Expected: Docker、PostgreSQL 和 Chromium 可用时测试通过；外部服务不可用时由现有 JUnit `Assumptions` 明确跳过，不得伪造通过结果。

- [ ] **Step 4: 检查截图与横向溢出**

确认 `target/workbench/` 中各视口 PNG 可读取且非纯色；检查所有按钮 `scrollWidth <= clientWidth + 1`；确认移动视图活动栏、运行输入框和面板标题无重叠。

---

## 规格覆盖自检

- 固定顶部品牌栏、Dockview 四面板、关闭后状态保留、`dockview.layout.v1` 版本回退：Tasks 1-3。
- 页面/外壳/Dockview 根禁止滚动，指定面板独立滚动，底部会话操作固定：Task 4 和 Task 6。
- `--accent` 驱动活动态、焦点、标签、drop target、主按钮，禁止硬编码蓝色：Tasks 3-4、Task 6。
- Vitest 面板注册/默认布局/序列化恢复/关闭恢复/主题映射，生产构建和多视口截图：Tasks 1、2、3、5、6。

计划完成并保存到 `docs/superpowers/plans/2026-08-16-workbench-dockview-layout.md`。执行时可选择：

1. Subagent-Driven：按任务逐项派发并在每项后复核。
2. Inline Execution：在当前会话按任务批次执行并设置检查点。
