# Workbench Editor Preview and Live Resize Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将文件预览提升为中心 Dockview 编辑器，并修复拖拽后的实时布局重算。

**Architecture:** `WorkspaceExplorerPanel` 只维护文件树并通过回调打开文件；新的 `WorkspaceEditorPanel` 维护标签和编辑状态。`WorkbenchDockLayout` 注册 editor 面板并用 ResizeObserver 驱动 Dockview `layout`，持久化白名单同步扩展。

**Tech Stack:** React 19、TypeScript、Dockview 8、Monaco、Vitest、ResizeObserver。

---

### Task 1: Extend the Dockview panel contract

**Files:**
- Modify: `agent-web/src/main/frontend/src/components/workbenchDockLayoutPersistence.ts`
- Modify: `agent-web/src/main/frontend/src/components/WorkbenchDockLayout.tsx`
- Test: `agent-web/src/main/frontend/src/components/workbenchDockLayoutPersistence.test.ts`

- [x] Add `editor` to the exact panel id union, title map, default registration and compact registration.
- [x] Write a failing persistence test that accepts `editor` and rejects unknown ids.
- [x] Remove `disableAutoResizing`; attach a ResizeObserver to the Dockview host and call `api.layout(width, height)` inside a requestAnimationFrame coalescer.
- [x] Verify the focused persistence/layout tests pass.

### Task 2: Extract file navigation from the sidebar

**Files:**
- Modify: `agent-web/src/main/frontend/src/components/WorkspaceExplorerPanel.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Test: `agent-web/src/main/frontend/src/components/WorkspaceExplorerPanel.test.tsx`

- [x] Add an `onOpenFile(path)` callback prop and a failing test that clicking a file calls it.
- [x] Keep directory loading and refresh in the sidebar; remove the embedded Monaco editor and save bar.
- [x] Pass the callback from Workbench through the Dockview panel context.
- [x] Verify the sidebar test passes and no editor DOM is rendered there.

### Task 3: Add the center editor panel

**Files:**
- Create: `agent-web/src/main/frontend/src/components/WorkspaceEditorPanel.tsx`
- Create: `agent-web/src/main/frontend/src/components/WorkspaceEditorPanel.test.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Modify: `agent-web/src/main/frontend/src/components/WorkbenchDockLayout.tsx`

- [x] Write failing tests for open/activate, dirty marker, save and close-all confirmation.
- [x] Implement a single panel with tabs keyed by exact workspace-relative path, using existing file APIs and Monaco Editor.
- [x] Add one close button per tab and a close-all action; prompt only when dirty files exist.
- [x] Wire file-open callbacks through a shared context and make the editor panel visible when a file opens.
- [x] Verify focused editor and workbench tests pass.

### Task 4: Normalize dynamic sizing and styles

**Files:**
- Modify: `agent-web/src/main/frontend/src/styles.css`
- Test: `agent-web/src/main/frontend/src/styles.test.ts`

- [x] Add explicit `min-width/min-height: 0` and independent overflow rules for Dockview, editor tabs and Monaco host.
- [x] Add a regression assertion for the Dockview host height contract and no fixed editor width.
- [x] Verify focused styles and editor tests, then run the full Vitest suite and Vite build.

### Task 5: Package and verify the running workbench

**Files:**
- Modify: `README.md`

- [x] Document center editor tabs and the live resize behavior.
- [x] Run `mvn -pl agent-web -am package -DskipTests`.
- [x] Rebuild `agent-web` with `docker compose -f docker-compose.local.yml --env-file .env up -d --build agent-web`.
- [ ] Verify a browser viewport after a Dockview drag/resize（本会话内置浏览器服务缺少内核资源，待服务恢复后执行）。
- [ ] Commit with `feat(workbench): add center editor and live dock resize`.
