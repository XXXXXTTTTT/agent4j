# 工作区开发闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** 让 Agent4J 从空项目创建或外部导入开始，完成文件浏览、文本编辑、受治理测试和 Trace/终端验收闭环。

**Architecture:** 在现有 `WorkspaceAccessService` 之上增加项目创建服务和相对路径文件服务；REST API 只暴露工作区相对路径和内容摘要。前端新增独立项目资源面板，保存通过 SHA-256 乐观并发控制，命令执行复用既有受治理 CLI 和 Run 证据面板。

**Tech Stack:** Java 21、Spring WebFlux、NIO `Path`/`Files`、JUnit 5、React、TypeScript、Monaco、Vitest/Testing Library。

---

### Task 1: 后端项目创建与安全路径服务

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/workspace/WorkspaceProjectService.java`
- Create: `agent-web/src/main/java/com/agent/web/workspace/WorkspaceFileService.java`
- Create: `agent-web/src/main/java/com/agent/web/workspace/WorkspaceFileEntry.java`
- Create: `agent-web/src/main/java/com/agent/web/workspace/WorkspaceFileContent.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/CreateProjectRequest.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/WorkspaceFileWriteRequest.java`
- Modify: `agent-web/src/main/java/com/agent/web/workspace/WorkspaceAccessService.java`
- Test: `agent-web/src/test/java/com/agent/web/workspace/WorkspaceProjectServiceTest.java`
- Test: `agent-web/src/test/java/com/agent/web/workspace/WorkspaceFileServiceTest.java`

- [ ] Write failing tests for creating a non-existing child directory, rejecting a separator in `directoryName`, listing directories before files with lexical order, rejecting `..`, rejecting symlinks, reading UTF-8, and rejecting SHA mismatch.
- [ ] Run `mvn -pl agent-web -Dtest=WorkspaceProjectServiceTest,WorkspaceFileServiceTest test`; confirm failures identify missing services.
- [ ] Implement the services with `Path.toRealPath`, `LinkOption.NOFOLLOW_LINKS`, a configured max byte count, SHA-256 via `MessageDigest`, and atomic temporary-file replacement.
- [ ] Run the focused tests and the existing workspace tests; confirm all pass under the Java 21 Maven Toolchain.

### Task 2: REST contracts and audit integration

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/controller/WorkspaceProjectController.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/WorkspaceFileController.java`
- Create: `agent-web/src/test/java/com/agent/web/controller/WorkspaceFileControllerTest.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`
- Modify: `agent-web/src/main/java/com/agent/web/audit/ConversationAuditEventType.java`

- [ ] Add tests for `POST /api/workspaces/projects`, file list/read/write status codes, workspace permission enforcement, 404, 409 and 422 responses.
- [ ] Run the controller tests and confirm they fail before controller beans and DTOs exist.
- [ ] Register services in `HarnessConfiguration`, expose the exact endpoints from the design, and emit audit events containing only relative path, byte count, hash and result.
- [ ] Run all `agent-web` tests and verify no audit event contains file content.

### Task 3: Frontend file API and project explorer

**Files:**
- Create: `agent-web/src/main/frontend/src/api/workspaceFilesApi.ts`
- Create: `agent-web/src/main/frontend/src/api/workspaceFilesApi.test.ts`
- Create: `agent-web/src/main/frontend/src/components/WorkspaceExplorerPanel.tsx`
- Create: `agent-web/src/main/frontend/src/components/WorkspaceExplorerPanel.test.tsx`
- Modify: `agent-web/src/main/frontend/src/api/contracts.ts`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Modify: `agent-web/src/main/frontend/src/styles.css`

- [ ] Add API decoder tests for exact file entry/content contracts and SHA conflict errors.
- [ ] Add component tests for activity switching, expand/collapse, ArrowUp/ArrowDown navigation, Enter expansion/opening, Ctrl+S save, and conflict state.
- [ ] Implement the API client and panel with a fixed header, scrollable tree/editor body, accessible tree roles, and Monaco text editing.
- [ ] Mount the panel in the existing project activity column without removing ConversationSidebar, and run the complete frontend test suite.

### Task 4: Empty-project EDD and acceptance evidence

**Files:**
- Modify: `.agent4j/acceptance/run-real-agent.ps1`
- Create: `.agent4j/acceptance/run-workspace-development-loop.ps1`
- Create: `docs/superpowers/evidence/2026-08-16-workspace-development-loop.md`

- [ ] Create an empty project through the new endpoint, write a minimal Java source/test through the file API, and assert the initial test fails.
- [ ] Submit the existing conversation/Code Agent task to repair the source, wait for completion, and assert `ops.exitCode=0`, reviewer approval, Trace node names, and terminal `BUILD SUCCESS`.
- [ ] Re-read the saved file through the API and verify its SHA-256 and content, then run Maven directly in the imported workspace with the Java 21 Toolchain.
- [ ] Record HTTP status, workspace ID, turn ID, run ID, model transport and terminal/Trace evidence without secrets.

### Task 5: Verification and milestone commit

- [ ] Run `git diff --check` and inspect `git status --short`.
- [ ] Run `mvn -pl agent-web -am test` and the frontend test/build commands.
- [ ] Run the real workspace EDD against the configured model gateway; report failures with their exact evidence instead of substituting mocked results.
- [ ] Stage only files belonging to this plan and commit with `feat(workspace): 建立项目开发闭环`.
