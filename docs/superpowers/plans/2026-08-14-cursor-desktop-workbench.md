# Cursor 风格桌面工作台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可连接本地 Agent4J 服务的 Electron 桌面应用和 Cursor 风格高密度工作台，同时保持既有受治理导入、会话与 Agent 运行链。

**Architecture:** Electron main process 负责 readiness、原生目录选择与安全 ZIP 归档；preload 仅暴露受限归档接口；现有 React workbench 通过既有 `/api/workspace-imports` 上传归档。Spring Boot 保持工作区、身份和权限的唯一权威。

**Tech Stack:** Electron、TypeScript、React 19、Vite、Vitest、fflate、现有 Spring Boot API。

---

### Task 1: 建立安全 Electron 桌面壳

**Files:**
- Create: `agent-desktop/package.json`
- Create: `agent-desktop/tsconfig.json`
- Create: `agent-desktop/vite.config.ts`
- Create: `agent-desktop/electron-builder.yml`
- Create: `agent-desktop/src/main/backend-health-probe.ts`
- Create: `agent-desktop/src/main/local-project-archive-service.ts`
- Create: `agent-desktop/src/main/index.ts`
- Create: `agent-desktop/src/preload/index.ts`
- Create: `agent-desktop/src/shared/desktop-bridge.ts`
- Create: `agent-desktop/src/main/backend-health-probe.test.ts`
- Create: `agent-desktop/src/main/local-project-archive-service.test.ts`
- Create: `agent-desktop/src/preload/index.test.ts`
- Modify: `.gitignore`

- [ ] 先写 readiness、ZIP 归档和 preload 白名单失败测试。
- [ ] 测试非 200、非法 JSON、`status != UP` 均不能连接；测试归档拒绝链接且不返回绝对路径；测试 preload 不泄露 Node API。
- [ ] 实现 Electron main/preload，创建带 context isolation、sandbox、无 Node integration 的窗口，并拒绝非本机 origin 导航。
- [ ] 执行 `Set-Location agent-desktop; npm ci; npm run test:run; npm run build`。
- [ ] 提交 `feat(desktop): add secure local desktop shell`。

### Task 2: 原生目录选择接入受治理导入链

**Files:**
- Modify: `agent-web/src/main/frontend/src/vite-env.d.ts`
- Modify: `agent-web/src/main/frontend/src/api/conversationApi.ts`
- Modify: `agent-web/src/main/frontend/src/hooks/useConversationWorkspace.ts`
- Modify: `agent-web/src/main/frontend/src/components/WorkspaceDialog.tsx`
- Test: `agent-web/src/main/frontend/src/components/WorkspaceDialog.test.tsx`
- Test: `agent-web/src/main/frontend/src/api/conversationApi.test.ts`

- [ ] 先写 Electron bridge 存在时的失败 UI 测试：显示原生选择动作、取消不提交、归档上传调用既有导入链且没有主机路径。
- [ ] 增加把 `Uint8Array` 转 `Blob` 的导入函数，multipart 字段固定为 `displayName`、`repositoryId`、`archive`。
- [ ] 浏览器路径继续保留 `webkitdirectory` 上传；桌面路径不调用 `POST /api/workspaces`。
- [ ] 执行定向 Vitest 和既有工作区导入后端回归测试。
- [ ] 提交 `feat(workspace): import desktop-selected projects safely`。

### Task 3: Cursor 风格工作台和身份扩展边界

**Files:**
- Create: `agent-web/src/main/frontend/src/components/DesktopConnectionStatus.tsx`
- Create: `agent-web/src/main/frontend/src/components/AccountPlaceholder.tsx`
- Modify: `agent-web/src/main/frontend/src/components/ConversationSidebar.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Modify: `agent-web/src/main/frontend/src/styles.css`
- Test: `agent-web/src/main/frontend/src/components/Workbench.test.tsx`
- Test: `agent-web/src/main/frontend/src/components/ConversationSidebar.test.tsx`

- [ ] 先写活动栏、项目栏、检查器、连接状态和本地身份占位测试。
- [ ] 实现活动栏与检查器切换；保留现有聊天、`/` 命令、Diff、Terminal、Trace、Capability 组件实例。
- [ ] 重构 CSS 为桌面 IDE 布局，增加小屏检查器收纳与无障碍焦点样式。
- [ ] 账户只展示服务端身份并预留菜单挂载点，不增加写身份接口。
- [ ] 执行全量 Vitest 和 `npm run build`。
- [ ] 提交 `feat(web): redesign workbench for desktop agent workflows`。

### Task 4: 真实桌面验收与发布说明

**Files:**
- Modify: `README.md`
- Test: `agent-desktop/src/main/index.test.ts`

- [ ] 为 BrowserWindow 安全选项、离线连接页与本机 origin 导航白名单增加测试。
- [ ] 启动 Docker Compose，验证 readiness 后启动 Electron 开发模式。
- [ ] 通过原生选择器导入一个小型 Git 项目，验证后端工作区路径属于受控导入根。
- [ ] 在同一运行中验证 Agent 对话、Terminal、Diff、Trace 和审批。
- [ ] 执行 `npm run package`、全量前端测试及真实 LLM EDD 脚本。
- [ ] 更新 README 的桌面启动、连接故障和打包说明。
- [ ] 提交 `docs(desktop): document desktop workbench acceptance`。
