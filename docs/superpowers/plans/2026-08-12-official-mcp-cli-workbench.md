# Official MCP and Governed CLI Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add official MCP catalog discovery with approval-gated workspace installations and expose the existing governed CLI catalog through the chat composer.

**Architecture:** Keep HTTP MCP configuration unchanged. Add a read-only GitHub catalog client and persistence-backed installation service; approved installations are launched only through a governed stdio transport rooted in the workspace. Add structured CLI catalog/run APIs that delegate authorization and execution to existing services, with the frontend consuming these APIs.

**Tech Stack:** Java 21, Spring Boot 3.3, JDBC/Flyway, Jackson, React/TypeScript, Vitest, JUnit 5.

---

### Task 1: Official catalog client and immutable metadata

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/mcp/catalog/OfficialMcpCatalogClient.java`
- Create: `agent-web/src/main/java/com/agent/web/mcp/catalog/OfficialMcpServerRecord.java`
- Create: `agent-web/src/test/java/com/agent/web/mcp/catalog/OfficialMcpCatalogClientTest.java`

- [ ] Write tests using fixed JSON fixtures for the exact root/`src` Contents responses, package metadata, README launch snippets, SHA retention, and rejection of missing service metadata.
- [ ] Implement bounded GitHub HTTP reads with virtual-thread execution, immutable records, exact field validation, and service records for the seven verified directories (`everything`, `fetch`, `filesystem`, `git`, `memory`, `sequentialthinking`, `time`).
- [ ] Run `mvn -pl agent-web -am -Dtest=OfficialMcpCatalogClientTest test` and commit `feat(mcp): add official catalog client`.

### Task 2: Catalog cache and installation persistence

**Files:**
- Create: `agent-web/src/main/resources/db/migration/V7__create_mcp_catalog_installations.sql`
- Create: `agent-web/src/main/java/com/agent/web/mcp/McpInstallationRecord.java`
- Create/modify: `agent-web/src/main/java/com/agent/web/mcp/McpInstallationRepository.java`, `agent-web/src/main/java/com/agent/web/mcp/McpInstallationService.java`
- Create: `agent-web/src/test/java/com/agent/web/mcp/McpInstallationServiceTest.java`

- [ ] Test workspace binding, explicit user-global scope, approval-required state, immutable source SHA/configuration snapshot, and secret-name-only persistence.
- [ ] Add Flyway tables for catalog snapshots, installations, and approval/audit state with workspace/user indexes and status checks.
- [ ] Implement JDBC repository/service using `WorkspaceAccessService`, with preview having no side effects and confirmation required before persistence.
- [ ] Run focused Java tests and commit `feat(mcp): persist approved installations`.

### Task 3: Governed MCP stdio transport and runtime registration

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/mcp/McpStdioTransport.java`
- Create: `agent-web/src/main/java/com/agent/web/mcp/McpInstallationRuntime.java`
- Create: `agent-core/src/test/java/com/agent/core/tool/mcp/McpStdioTransportTest.java`
- Create: `agent-web/src/test/java/com/agent/web/mcp/McpInstallationRuntimeTest.java`

- [ ] Test JSON-RPC request/notification framing, process timeout/exit, bounded output, and cleanup.
- [ ] Implement a process-backed `McpTransport` with `ProcessBuilder` receiving only validated command/args from an approved snapshot; set workspace directory and virtual-thread reader/writer.
- [ ] Reuse `McpClient` and `McpToolRegistryAdapter`, preserve risk/capability policy, and record startup/stop failures.
- [ ] Run focused tests and commit `feat(mcp): bridge approved stdio servers`.

### Task 4: MCP REST endpoints and frontend installation view

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/controller/McpCatalogController.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/McpInstallationView.java`
- Modify: `agent-web/src/main/frontend/src/api/conversationApi.ts`, `contracts.ts`
- Create: `agent-web/src/main/frontend/src/components/McpCatalogPanel.tsx`
- Create: `agent-web/src/main/frontend/src/components/McpCatalogPanel.test.tsx`

- [ ] Add catalog, preview, list, confirm-install, and uninstall endpoints with exact DTO contracts and workspace permission checks.
- [ ] Render source URL, SHA, version, launch mode, permissions, risk, and approval state before confirmation.
- [ ] Test preview/no-side-effect, confirmation, and uninstall UI/API flows.
- [ ] Run frontend tests and commit `feat(web): add mcp catalog workbench`.

### Task 5: Governed CLI catalog/run API

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/controller/CliCommandController.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/CliCommandView.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/CliRunRequest.java`
- Create: `agent-web/src/test/java/com/agent/web/controller/CliCommandControllerTest.java`

- [ ] Test catalog filtering by workspace/actor and structured run authorization for READ_ONLY, MUTATING, and DESTRUCTIVE commands.
- [ ] Expose `GET /api/workspaces/{workspaceId}/cli/commands` and `POST /api/workspaces/{workspaceId}/cli/runs`; reject arbitrary shell text and out-of-root targets.
- [ ] Reuse existing run creation, `CliCommandCatalog.authorize`, approval interrupt, terminal SSE and trace projection.
- [ ] Run focused tests and commit `feat(web): expose governed cli runs`.

### Task 6: Chat `/` command picker

**Files:**
- Modify: `agent-web/src/main/frontend/src/api/conversationApi.ts`, `contracts.ts`
- Modify: `agent-web/src/main/frontend/src/components/ConversationComposer.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.css`
- Create/modify: `agent-web/src/main/frontend/src/components/ConversationComposer.test.tsx`

- [ ] Test slash activation, exact command selection, parameter entry, risk/approval preview, submit routing, and ordinary chat regression.
- [ ] Add keyboard-accessible command list filtered from the current workspace catalog; render parameter controls from server metadata.
- [ ] Submit selected commands as structured CLI runs and follow the returned run ID through existing terminal/trace state.
- [ ] Run `npm test -- --run` in `agent-web/src/main/frontend` and commit `feat(web): add slash cli command picker`.

### Task 7: Integration, EDD evidence, and quality review

**Files:**
- Create/modify: `agent-web/src/test/java/com/agent/web/mcp/McpCatalogIntegrationTest.java`
- Create: `agent-eval/src/test/resources/benchmarks/mcp-cli-workbench.json`
- Modify: `README.md`

- [ ] Add an integration test with an in-process stdio MCP fixture and governed CLI fixture, asserting audit/trace/run evidence.
- [ ] Add EDD scenarios for catalog discovery, rejected unapproved install, approved workspace install, slash CLI execution, and failure recovery.
- [ ] Run Maven, frontend tests, and the real configured LLM EDD command when API configuration is available; capture evidence paths and Beijing-time display behavior.
- [ ] Update setup/approval documentation, run `git diff --check`, `git status --short`, and complete a final code review before the final conventional commit.
