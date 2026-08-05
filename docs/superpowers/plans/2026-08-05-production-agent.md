# Production Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Web 工作台连接到真实 `code-agent` 执行图，让自然语言任务驱动模型规划、代码 Diff、沙箱命令和审查，并展示完整可审计证据。

**Architecture:** 保留现有 StateGraph、AgentRunService、Checkpoint 和 WebSocket 生命周期。新增构造器注入的生产 GraphFactory；Coder 在单节点内执行严格 JSON 代码生成、工作区快照和 JGit Diff；Reviewer 支持无 URL 的代码证据模式。所有请求、响应、工具输入输出和错误写入 AgentState，前端从快照/历史渲染。

**Tech Stack:** Java 21, Spring Boot 3.3, JavaParser/JGit, Docker-Java/pty4j, Playwright, OpenAI-compatible LlmClient, React/TypeScript, Vitest.

---

### Task 1: 工作区快照与 Coder 模型协议

**Files:**
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/ast/WorkspaceSnapshot.java`
- Create: `agent-sandbox/src/main/java/com/agent/sandbox/ast/WorkspaceSnapshotService.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/CoderNode.java`
- Test: `agent-sandbox/src/test/java/com/agent/sandbox/ast/WorkspaceSnapshotServiceTest.java`
- Test: `agent-core/src/test/java/com/agent/core/nodes/CoderNodeTest.java`

- [ ] Write failing snapshot tests for deterministic, bounded Git-worktree file capture and excluded directories.
- [ ] Run the focused tests and verify missing classes/protocol behavior fails.
- [ ] Implement snapshot service with exact path containment, text-file filtering, file-count and byte limits.
- [ ] Add a constructor-injected Coder model route while preserving the existing diff-only constructor used by current tests.
- [ ] Parse the exact `summary`, `unifiedDiff`, `command` JSON object; reject unknown/missing/wrong fields before applying anything.
- [ ] Store `coder.request`, `coder.response`, `coder.command`, and tool result variables; preserve complete stack traces on errors.
- [ ] Run focused Java tests and commit `feat(core): generate and apply model code changes`.

### Task 2: Reviewer evidence modes and repair route

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/nodes/ReviewerNode.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/CoderNode.java`
- Test: `agent-core/src/test/java/com/agent/core/nodes/ReviewerNodeTest.java`
- Test: `agent-core/src/test/java/com/agent/core/nodes/CoderOpsReviewerGraphTest.java`

- [ ] Add a failing test proving Reviewer can evaluate Ops/code evidence with an absent `reviewer.url`.
- [ ] Add failing tests for request/response evidence and an explicit false-review repair route.
- [ ] Implement optional browser acquisition, strict model response capture, and review evidence variables.
- [ ] Add bounded repair counter and conditional StateGraph route in the production graph test fixture.
- [ ] Run focused Core tests and commit `feat(core): expose reviewer evidence and repair context`.

### Task 3: Production Spring Graph wiring

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/config/ProductionAgentProperties.java`
- Create: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ModelGatewayConfiguration.java`
- Modify: `agent-web/src/main/resources/application.properties`
- Modify: `agent-web/pom.xml` only if required by existing module APIs
- Test: `agent-web/src/test/java/com/agent/web/config/ProductionGraphConfigurationTest.java`

- [ ] Write a failing Spring configuration test that creates `code-agent` with constructor-injected Planner, Coder, Ops and Reviewer.
- [ ] Verify the test fails while production GraphFactory and resource beans are absent.
- [ ] Implement exact properties for workspace, repository/user scope, Docker image/container path, Bash path, timeouts and optional reviewer URL.
- [ ] Register empty MemoryContextProvider fallback, SandboxTerminalService, PlaywrightBrowserService, AstService and production GraphFactory with lifecycle cleanup.
- [ ] Keep `demo-agent` available only under its existing explicit sample property.
- [ ] Run the focused web tests and commit `feat(web): wire production code agent graph`.

### Task 4: Web API default task contract

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/controller/StartRunRequest.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/RunController.java`
- Create or modify: `agent-web/src/main/java/com/agent/web/controller/CodeAgentStartRequest.java`
- Test: `agent-web/src/test/java/com/agent/web/controller/RunControllerTest.java`

- [ ] Write failing API tests for a task-first request that produces exact `code-agent` initial variables.
- [ ] Implement a dedicated task-first endpoint or request mapping without changing the low-level advanced endpoint contract.
- [ ] Validate workspace path, task, repositoryId and userId; pass optional reviewer URL exactly.
- [ ] Run controller tests and commit `feat(web): expose task-first code agent endpoint`.

### Task 5: Frontend evidence console

**Files:**
- Modify: `agent-web/src/main/frontend/src/components/RunLauncher.tsx`
- Modify: `agent-web/src/main/frontend/src/components/AgentConversation.tsx`
- Modify: `agent-web/src/main/frontend/src/components/CodeDiffPanel.tsx`
- Modify: `agent-web/src/main/frontend/src/components/ReviewEvidencePanel.tsx`
- Modify: `agent-web/src/main/frontend/src/components/TraceTimeline.tsx`
- Modify: corresponding `*.test.tsx` files and API contracts when fields change

- [ ] Add failing Vitest tests proving the launcher defaults to `code-agent` and sends the task into `planner.task`.
- [ ] Add failing tests for Planner/Coder request and response blocks, command evidence, reviewer evidence and errors.
- [ ] Implement evidence rendering with bounded preformatted panels and no demo-only labels in the production path.
- [ ] Keep advanced JSON launch available for debugging and explicitly label `demo-agent` as Demo.
- [ ] Run `npm run test:run` and `npm run build`; commit `feat(web): render production agent evidence`.

### Task 6: End-to-end verification and environment docs

**Files:**
- Modify: `.env.example`
- Modify: `README.md`
- Modify: `docs/ENGINEERING_PITFALLS.md`
- Test: production Graph integration tests and browser test fixtures

- [ ] Add integration coverage with a mock OpenAI-compatible endpoint for Planner, Coder and Reviewer.
- [ ] Run Java 21 Maven tests for all modules and frontend tests/build.
- [ ] Start local Compose using the existing `.env` workflow; verify PostgreSQL, backend and frontend ports from the actual compose files.
- [ ] Verify a real browser task submission shows model requests, Diff, command logs, review evidence and final output; run Docker integration where Engine is available.
- [ ] Update README and pitfalls document with exact setup, state keys and known operational boundaries.
- [ ] Review `git status`, ensure only scoped files are staged, and commit `test(agent): verify production execution loop`.

