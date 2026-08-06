# Persistent Conversations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent4J 增加绑定用户和工作区的 PostgreSQL 持久化会话，使每轮独立 Run 能恢复历史、连续问答、刷新重载并执行精确权限校验。

**Architecture:** `agent-web` 以 PostgreSQL Conversation/Turn 聚合作为会话唯一权威源，`agent-core` 只接收框架无关的历史 `ChatMessage`。每轮提交创建新 Run，通过 Trace 终态投影回写 Turn；前端用 Conversation API 驱动侧栏和消息流，现有 Run/Trace/Terminal 协议继续负责单轮执行证据。

**Tech Stack:** Java 21 records/virtual threads, Spring Boot 3.3 WebFlux/JDBC, PostgreSQL 16/Flyway, JUnit 5/Testcontainers, React 19/TypeScript/Vitest/Testing Library

---

## File Map

### `agent-core`

- Create `agent-core/src/main/java/com/agent/core/conversation/ConversationContext.java`: 有界历史消息值对象。
- Create `agent-core/src/main/java/com/agent/core/conversation/ConversationContextProvider.java`: 框架无关历史加载端口。
- Modify `agent-core/src/main/java/com/agent/core/nodes/PlannerNode.java`: 将历史消息注入路由、问答和规划请求，并把本轮用户/助手消息写回状态。
- Modify `agent-core/src/test/java/com/agent/core/nodes/PlannerNodeTest.java`: 覆盖多轮上下文、消息顺序和不重复当前输入。

### `agent-web` backend

- Create `agent-web/src/main/resources/db/migration/V2__create_conversation_tables.sql`: 用户、工作区、成员、会话、轮次表与索引。
- Create `agent-web/src/main/java/com/agent/web/identity/Actor.java`, `ActorResolver.java`, `ConfiguredActorResolver.java`: 可替换身份边界。
- Create `agent-web/src/main/java/com/agent/web/workspace/WorkspacePermission.java`, `WorkspaceRecord.java`, `WorkspaceAccessService.java`, `WorkspaceBootstrap.java`: 工作区领域、权限和默认数据。
- Create `agent-web/src/main/java/com/agent/web/conversation/ConversationStatus.java`, `ConversationTurnStatus.java`, `ConversationRecord.java`, `ConversationTurnRecord.java`: 精确会话领域记录。
- Create `agent-web/src/main/java/com/agent/web/conversation/ConversationRepository.java`, `JdbcConversationRepository.java`: 事务持久化端口与 JDBC 实现。
- Create `agent-web/src/main/java/com/agent/web/conversation/JdbcConversationContextProvider.java`: 将已完成 Turn 转换为有界 `ChatMessage`。
- Create `agent-web/src/main/java/com/agent/web/conversation/ConversationService.java`: 权限校验、创建/归档会话、提交轮次和启动 Run。
- Create `agent-web/src/main/java/com/agent/web/conversation/ConversationRunProjector.java`: Run 终态到 Turn 的幂等投影。
- Create `agent-web/src/main/java/com/agent/web/controller/IdentityController.java`, `WorkspaceController.java`, `ConversationController.java` 及其精确 request/view records。
- Modify `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`: 装配身份、Conversation 服务、投影器和 Trace 组合发布器。
- Modify `agent-web/src/main/java/com/agent/web/controller/RunController.java`: 旧 code-agent 入口使用 ActorResolver，禁止请求覆盖用户。
- Modify `agent-web/src/main/java/com/agent/web/controller/CodeAgentStartRequest.java`: 移除 `userId` 请求字段。
- Add focused tests under `agent-web/src/test/java/com/agent/web/identity`, `workspace`, `conversation`, `controller`.

### `agent-web` frontend

- Modify `agent-web/src/main/frontend/src/api/contracts.ts`: 增加 Actor、Workspace、Conversation、ConversationTurn 精确合约。
- Create `agent-web/src/main/frontend/src/api/conversationApi.ts` and test: 严格解码会话 REST 数据。
- Modify `agent-web/src/main/frontend/src/hooks/useRunWorkbench.ts`: 暴露 `followRun(runId)`，复用单轮实时连接。
- Create `agent-web/src/main/frontend/src/hooks/useConversationWorkspace.ts` and test: 加载工作区/会话/Turn、URL 恢复和终态刷新。
- Create `agent-web/src/main/frontend/src/components/ConversationSidebar.tsx` and test: 工作区选择、搜索、新建、归档和移动抽屉。
- Modify `App.tsx`, `Workbench.tsx`, `RunLauncher.tsx`, `AgentConversation.tsx`, `styles.css`: 以持久化 Turn 驱动 UI，同时保留当前 Run 证据。

### Documentation

- Modify `README.md`: 增加默认用户、工作区和持久化会话使用说明。
- Modify `docs/ENGINEERING_PITFALLS.md`: 记录 Run 与 Conversation 混淆、事件投影窗口和权限范围踩坑。

---

### Task 1: Core conversation context and Planner history

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/conversation/ConversationContext.java`
- Create: `agent-core/src/main/java/com/agent/core/conversation/ConversationContextProvider.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/PlannerNode.java`
- Modify: `agent-core/src/test/java/com/agent/core/nodes/PlannerNodeTest.java`

- [ ] **Step 1: Write failing Planner tests**

Add tests that create state with `ChatMessage.user("我住在南昌")` and
`ChatMessage.assistant("已记住")`, execute a second task `我住在哪里？`, and assert the routed
request messages are exactly system, previous user, previous assistant, current user. Add the same
assertion for `TaskType.CODE`, and assert returned state ends with exactly one current user and one
assistant response.

- [ ] **Step 2: Verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; mvn -pl agent-core -Dtest=PlannerNodeTest test
```

Expected: FAIL because `PlannerNode` currently sends only system + current task and does not append
conversation messages.

- [ ] **Step 3: Add immutable context types**

Implement:

```java
public record ConversationContext(
        List<ChatMessage> messages,
        int turnCount,
        boolean truncated) {
    public ConversationContext {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        if (turnCount < 0) {
            throw new IllegalArgumentException("turnCount 不能为负数");
        }
    }
}
```

and the exact provider signature from the design spec.

- [ ] **Step 4: Update Planner message assembly**

Add a private method that creates an immutable list in exact order: system instruction, all
`state.messages()`, current user. `answerChat` and semantic routing use that history. Successful
chat and agent paths append `ChatMessage.user(task)` and the assistant response exactly once.

- [ ] **Step 5: Verify GREEN and commit**

Run the focused test and all `agent-core` tests, then commit:

```text
feat(core): preserve conversation history in planner
```

### Task 2: Flyway schema and identity boundary

**Files:**
- Create: `agent-web/src/main/resources/db/migration/V2__create_conversation_tables.sql`
- Create: `agent-web/src/main/java/com/agent/web/identity/Actor.java`
- Create: `agent-web/src/main/java/com/agent/web/identity/ActorResolver.java`
- Create: `agent-web/src/main/java/com/agent/web/identity/ConfiguredActorResolver.java`
- Create: `agent-web/src/main/java/com/agent/web/workspace/WorkspacePermission.java`
- Test: `agent-web/src/test/java/com/agent/web/identity/ConfiguredActorResolverTest.java`
- Test: `agent-web/src/test/java/com/agent/web/conversation/ConversationMigrationIntegrationTest.java`

- [ ] **Step 1: Write failing migration and identity tests**

The PostgreSQL Testcontainers test applies Flyway locations from `agent-web`, queries
`information_schema` for all five exact table names, verifies all four status/permission check
constraints by rejected inserts, and verifies foreign keys. Identity test asserts blank configured
user IDs fail and exact case is preserved.

- [ ] **Step 2: Verify RED**

Run the two tests with JDK 21. Expected: missing V2 tables and identity classes.

- [ ] **Step 3: Implement V2 and configured actor**

Create the exact columns, constraints and indexes from the design. `ConfiguredActorResolver`
returns the exact configured `userId`; it does not accept headers or request-body identity.

- [ ] **Step 4: Verify GREEN and commit**

Commit:

```text
feat(web): add conversation identity schema
```

### Task 3: Workspace persistence, bootstrap and permissions

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/workspace/WorkspaceRecord.java`
- Create: `agent-web/src/main/java/com/agent/web/workspace/WorkspaceAccessService.java`
- Create: `agent-web/src/main/java/com/agent/web/workspace/WorkspaceBootstrap.java`
- Create/Modify: `agent-web/src/main/java/com/agent/web/conversation/ConversationRepository.java`
- Create/Modify: `agent-web/src/main/java/com/agent/web/conversation/JdbcConversationRepository.java`
- Test: `agent-web/src/test/java/com/agent/web/workspace/WorkspaceAccessServiceTest.java`
- Test: `agent-web/src/test/java/com/agent/web/conversation/JdbcConversationRepositoryIntegrationTest.java`

- [ ] **Step 1: Write failing repository tests**

Cover idempotent user/default workspace bootstrap, exact `VIEWER`/`OPERATOR`/`OWNER` ordering,
workspace listing isolation, disabled user rejection, no-member not-found behavior, and
`toRealPath()` containment under the configured root.

- [ ] **Step 2: Verify RED**

Expected: missing repository and access service.

- [ ] **Step 3: Implement minimal JDBC workspace operations**

Use bound SQL parameters and `TransactionTemplate`. Workspace creation inserts user, workspace and
OWNER membership in one transaction. `requirePermission` reads the exact membership row and compares
enum authority without case conversion.

- [ ] **Step 4: Verify GREEN and commit**

Commit:

```text
feat(web): enforce workspace membership permissions
```

### Task 4: Conversation repository and bounded context

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/conversation/ConversationStatus.java`
- Create: `agent-web/src/main/java/com/agent/web/conversation/ConversationTurnStatus.java`
- Create: `agent-web/src/main/java/com/agent/web/conversation/ConversationRecord.java`
- Create: `agent-web/src/main/java/com/agent/web/conversation/ConversationTurnRecord.java`
- Complete: `agent-web/src/main/java/com/agent/web/conversation/ConversationRepository.java`
- Complete: `agent-web/src/main/java/com/agent/web/conversation/JdbcConversationRepository.java`
- Create: `agent-web/src/main/java/com/agent/web/conversation/JdbcConversationContextProvider.java`
- Test: `agent-web/src/test/java/com/agent/web/conversation/JdbcConversationRepositoryIntegrationTest.java`
- Test: `agent-web/src/test/java/com/agent/web/conversation/JdbcConversationContextProviderTest.java`

- [ ] **Step 1: Write failing conversation tests**

Cover title derivation at 80 Unicode code points, updated-at ordering, exact title search, archive
conflict, sequential `turn_index`, concurrent active-turn conflict, all Turn state transitions and
idempotent terminal updates.

- [ ] **Step 2: Write failing context budget tests**

Insert completed and incomplete turns. Assert only completed user/assistant pairs are emitted,
oldest complete pairs are removed when `maxTurns` or `maxCharacters` is exceeded, ordering is
stable, and `truncated` is exact.

- [ ] **Step 3: Verify RED**

Expected: missing conversation repository and provider behavior.

- [ ] **Step 4: Implement repository and provider**

Lock `agent_conversations` with `select ... for update` before allocating a Turn. Use Java code
points for title truncation. Context provider returns `ChatMessage.user` followed by
`ChatMessage.assistant` for each retained completed Turn.

- [ ] **Step 5: Verify GREEN and commit**

Commit:

```text
feat(web): persist bounded conversation turns
```

### Task 5: Conversation service and Run terminal projection

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/conversation/ConversationService.java`
- Create: `agent-web/src/main/java/com/agent/web/conversation/ConversationRunProjector.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`
- Test: `agent-web/src/test/java/com/agent/web/conversation/ConversationServiceTest.java`
- Test: `agent-web/src/test/java/com/agent/web/conversation/ConversationRunProjectorTest.java`

- [ ] **Step 1: Write failing service tests**

Use fakes for repository, actor, context and Run service boundaries. Assert submission state contains
exact keys `planner.task`, `planner.repositoryId`, `planner.userId`, `coder.workspacePath`,
`conversation.id`, `conversation.turnId`; assert history messages are preserved and client identity
cannot enter state.

- [ ] **Step 2: Write failing projector tests**

For `COMPLETED`, assert assistant resolution order is exactly `final_response`,
`reviewer.feedback`, `reviewer.summary`, `planner.response`. For `FAILED` and `REJECTED`, assert full
error persistence. Repeating the same event must not create or mutate another Turn.

- [ ] **Step 3: Verify RED**

Expected: missing service/projector.

- [ ] **Step 4: Implement service and projector**

Create PENDING Turn before Run start, mark RUNNING with returned `runId`, and mark FAILED with full
stack on start failure. Add projector to `RunLifecycleEventPublisher` delegates after the in-memory
Trace bus so UI streaming remains independent of database projection failures.

- [ ] **Step 5: Verify GREEN and commit**

Commit:

```text
feat(web): project agent runs into conversations
```

### Task 6: Conversation REST API and legacy identity hardening

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/controller/IdentityController.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/WorkspaceController.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/ConversationController.java`
- Create: exact request/view records in `com.agent.web.controller`
- Modify: `agent-web/src/main/java/com/agent/web/controller/RunController.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/CodeAgentStartRequest.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/RunExceptionHandler.java`
- Test: `agent-web/src/test/java/com/agent/web/controller/ConversationControllerTest.java`
- Test: `agent-web/src/test/java/com/agent/web/controller/RunControllerTest.java`

- [ ] **Step 1: Write failing WebTestClient tests**

Cover exact JSON fields for all endpoints, unknown-field rejection, blank validation, 403/404/409
mapping, Actor scoping and returned `runId`. Add regression test proving `userId` in legacy request is
rejected as an unknown field.

- [ ] **Step 2: Verify RED**

Expected: endpoint not found and legacy request still accepts `userId`.

- [ ] **Step 3: Implement controllers and exception mapping**

Controllers receive only validated records and delegate to services. They never choose or normalize
IDs. Preserve all existing Run endpoints.

- [ ] **Step 4: Verify GREEN and commit**

Commit:

```text
feat(web): expose persistent conversation api
```

### Task 7: Frontend strict API contracts

**Files:**
- Modify: `agent-web/src/main/frontend/src/api/contracts.ts`
- Create: `agent-web/src/main/frontend/src/api/conversationApi.ts`
- Create: `agent-web/src/main/frontend/src/api/conversationApi.test.ts`
- Modify: `agent-web/src/main/frontend/src/hooks/useRunWorkbench.ts`
- Modify: `agent-web/src/main/frontend/src/hooks/useRunWorkbench.test.tsx`

- [ ] **Step 1: Write failing decoder and followRun tests**

Test exact-key rejection, enum rejection, chronological Turn validation and `followRun(runId)` loading
the latest Run/history before opening sockets.

- [ ] **Step 2: Verify RED**

Run `npm run test:run -- conversationApi.test.ts useRunWorkbench.test.tsx`; expect missing exports.

- [ ] **Step 3: Implement strict decoders and `followRun`**

Use the existing `objectAt`, `exactKeys`, enum and scalar helpers; move shared helpers only if tests
stay green. `followRun` must increment `operationRef`, load authority, set refs and connect exactly
once.

- [ ] **Step 4: Verify GREEN and commit**

Commit:

```text
feat(web-ui): add conversation api contracts
```

### Task 8: Frontend workspace and conversation state

**Files:**
- Create: `agent-web/src/main/frontend/src/hooks/useConversationWorkspace.ts`
- Create: `agent-web/src/main/frontend/src/hooks/useConversationWorkspace.test.tsx`
- Modify: `agent-web/src/main/frontend/src/App.tsx`

- [ ] **Step 1: Write failing hook tests**

Cover initial Actor/workspace load, URL `conversationId` restoration, invalid URL cleanup, workspace
switch, search, new conversation, first-turn create+submit, subsequent-turn submit, terminal Run
following, and terminal refresh of Turns/conversation list.

- [ ] **Step 2: Verify RED**

Expected: hook missing.

- [ ] **Step 3: Implement orchestration hook**

Keep server data authoritative. The hook may keep only selected IDs, loading/error flags and fetched
views in React state. It must never persist message content in `localStorage`.

- [ ] **Step 4: Verify GREEN and commit**

Commit:

```text
feat(web-ui): coordinate persistent conversations
```

### Task 9: Conversation sidebar and persisted message UX

**Files:**
- Create: `agent-web/src/main/frontend/src/components/ConversationSidebar.tsx`
- Create: `agent-web/src/main/frontend/src/components/ConversationSidebar.test.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.tsx`
- Modify: `agent-web/src/main/frontend/src/components/Workbench.test.tsx`
- Modify: `agent-web/src/main/frontend/src/components/RunLauncher.tsx`
- Modify: `agent-web/src/main/frontend/src/components/RunLauncher.test.tsx`
- Modify: `agent-web/src/main/frontend/src/components/AgentConversation.tsx`
- Modify: `agent-web/src/main/frontend/src/styles.css`

- [ ] **Step 1: Write failing user-flow tests**

Render the real composed controller state and assert: workspace selector is visible; searchable
conversation list shows title/time; selecting loads persisted Turn messages; composer submits into
the selected conversation; current Run evidence remains visible; mobile menu has accessible open and
close controls.

- [ ] **Step 2: Verify RED**

Expected: missing sidebar and Turn-driven rendering.

- [ ] **Step 3: Implement work-focused layout**

Use Lucide icons, a 264px desktop sidebar, existing quiet green/neutral palette, one-line ellipsis for
conversation titles, and a responsive drawer below 900px. Render persisted Turns before live current
Run evidence; do not nest cards or hide the task composer.

- [ ] **Step 4: Verify GREEN and commit**

Commit:

```text
feat(web-ui): render workspace conversation history
```

### Task 10: Integration, documentation and black-box acceptance

**Files:**
- Create: `agent-web/src/test/java/com/agent/web/conversation/ConversationFlowIntegrationTest.java`
- Modify: `README.md`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] **Step 1: Write failing integration flow**

With real PostgreSQL and a deterministic GraphFactory, bootstrap a user/workspace, create a
conversation, submit two turns, and assert the second Planner request contains the first user and
assistant messages after constructing a fresh controller/service instance against the same database.

- [ ] **Step 2: Verify RED then GREEN**

Run the focused integration test before its missing wiring is implemented, complete only the required
wiring/reconciliation, and rerun until green.

- [ ] **Step 3: Update public documentation**

Document Conversation vs Run, configured local identity, workspace ownership, persistence across
restart, API examples and the exact Docker quick-start behavior. Add the implementation pitfalls to
the engineering retrospective.

- [ ] **Step 4: Run full verification**

Use JDK 21 and run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn clean verify
git -c safe.directory=D:/agent4j diff --check
docker compose -f docker-compose.local.yml --env-file .env up -d --build
docker compose -f docker-compose.local.yml --env-file .env ps
```

Perform the exact two-turn black-box test against the running API, restart only `agent-web`, repeat
the history GET, and verify PostgreSQL rows. Run Playwright desktop/mobile screenshots and confirm no
overlap, blank panel or hidden composer. Stop only the project Compose stack after evidence capture.

- [ ] **Step 5: Final atomic commit**

Review `git status`, exclude logs, `.env`, build outputs and screenshots, then commit:

```text
test(conversation): verify persistent multi-turn workflow
```

Finally run `git log -10 --oneline` and ensure every milestone uses Conventional Commits.
