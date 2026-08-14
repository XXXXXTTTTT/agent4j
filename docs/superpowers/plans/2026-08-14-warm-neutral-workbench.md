# 暖中性桌面工作台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除混合主题并把 Agent4J 工作台重构为暖中性、可收纳、可扫描的桌面 Agent 界面。

**Architecture:** 保留现有 React 组件与后端调用链，只重构工作台布局、主题 token、证据呈现默认状态和侧栏错误反馈。前端通过 CSS 媒体查询和 Workbench 的收纳状态适配桌面与移动视口。

**Tech Stack:** React 19、TypeScript、Vite、Vitest、lucide-react、现有 Markdown/Terminal/Diff/Trace 组件。

---

### Task 0: 规格审查门禁

**Files:**
- Review: `docs/superpowers/specs/2026-08-14-warm-neutral-workbench-design.md`
- Review: `docs/superpowers/plans/2026-08-14-warm-neutral-workbench.md`

- [ ] Sol 子代理检查视觉决策是否与 Cursor 官网证据一致、是否避免复制品牌资产、是否覆盖现有 Agent 能力和视口验收。
- [ ] 只有规格审查无阻断项后才进入 Task 1。

### Task 1: 建立单一暖中性主题和响应式布局

**Files:**
- Modify: `agent-web/src/main/frontend/src/styles.css`
- Test: `agent-web/src/main/frontend/src/components/Workbench.test.tsx`

- [ ] 写测试断言工作台使用暖中性主题标记、顶栏 44px 语义类、活动栏焦点和检查器收纳按钮。
- [ ] 删除旧的浅色规则与末尾覆盖补丁，按 token、布局、组件、状态、媒体查询五段重写 CSS；保证所有既有 class 都有同一主题下的背景和文字色。
- [ ] 将布局改为 48px 活动栏、248px 项目栏、中心自适应、380px 检查器；新增检查器收纳态和 1280/760px 媒体查询。
- [ ] 运行定向测试和 `npm run build`。
- [ ] 提交 `feat(web): establish warm neutral workbench theme`。

### Task 2: 优化 Workbench 上下文切换和可访问性

**Files:**
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Modify: `agent-web/src/main/frontend/src/components/ConversationSidebar.tsx`
- Test: `agent-web/src/main/frontend/src/components/Workbench.test.tsx`
- Test: `agent-web/src/main/frontend/src/components/ConversationSidebar.test.tsx`

- [ ] 写测试断言检查器可通过按钮收纳/展开，当前活动栏按钮拥有 `aria-current="page"` 与可见选中态，删除失败会显示错误。
- [ ] 在 Workbench 增加检查器收纳状态、紧凑顶栏工作区/会话上下文和收纳按钮；保留所有真实检查器组件实例。
- [ ] 在侧栏删除/归档/切换失败时保留 controller 错误提示；将归档筛选收进明确的低频操作区，不改变 API 字段。
- [ ] 运行定向测试和构建。
- [ ] 提交 `feat(web): clarify desktop workbench context switching`。

### Task 3: 降低 Agent 证据噪声并稳定 Composer

**Files:**
- Modify: `agent-web/src/main/frontend/src/components/AgentConversation.tsx`
- Modify: `agent-web/src/main/frontend/src/components/ConversationComposer.tsx`
- Test: `agent-web/src/main/frontend/src/components/Workbench.test.tsx`

- [ ] 写测试断言 Planner/Coder 原始请求和响应默认折叠，最终回答、命令风险和模型选择仍可见。
- [ ] 将原始请求/响应的 `details` 移除默认 `open`，保持错误、摘要、文件、终端摘要和图片工件可见；不修改变量键名或数据结构。
- [ ] 将 Composer 的上下文、模型选择、命令预览和发送动作统一到新主题 token，保持 `/` 键盘上下选择、Enter、Escape 和受治理 CLI 提交行为。
- [ ] 运行前端全量测试和构建。
- [ ] 提交 `feat(web): prioritize agent answers over raw evidence`。

### Task 4: 多视口验收和发布说明

**Files:**
- Modify: `README.md`
- Create: `agent-web/src/main/frontend/src/components/Workbench.visual.test.tsx`

- [ ] 增加基于 jsdom 的布局语义回归测试，覆盖收纳态、移动态 class 和主输入可访问名称。
- [ ] 在本地运行 `npm run test:run` 与 `npm run build`，使用 Electron/可用浏览器对 1440x900、1024x768、390x844 验收无横向溢出和混合白色控件。
- [ ] 更新 README 的桌面工作台视觉和检查器收纳说明，不宣称不存在的截图或模型能力。
- [ ] 提交 `docs(web): document warm neutral workbench acceptance`。
