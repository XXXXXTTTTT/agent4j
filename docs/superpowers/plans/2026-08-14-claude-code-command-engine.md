# Claude Code 风格指令引擎实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Agent4J 中实现可注册、可审计、双通道且可由工作区/全局 Markdown 扩展的 Slash Command 引擎。

**Architecture:** `agent-core` 提供无 Spring 依赖的解析、注册、授权和分发契约；系统 Handler 只返回本地结构化结果，工作流 Handler 通过注入的桥接端口进入既有会话/Graph。`agent-web` 负责配置、REST 和前端实时补全；Markdown Loader 负责安全加载并以 Registry 修订快照提供发现能力。

**Tech Stack:** Java 21 records、JUnit 5/AssertJ、Spring Boot WebFlux、Jackson、现有 PostgreSQL Checkpointer/ConversationService、React/TypeScript/Vitest。

---

### Task 1: Core command contracts and parser

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/command/CommandChannel.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandSource.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandPermission.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandParameter.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandDefinition.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandInvocation.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandParseException.java`
- Create: `agent-core/src/main/java/com/agent/core/command/SlashCommandParser.java`
- Test: `agent-core/src/test/java/com/agent/core/command/SlashCommandParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void parsesExactNameAndQuotedArgumentsWithoutSendingAnything() {
    CommandInvocation invocation = new SlashCommandParser().parse(
            "/review \"security pass\" --fix");

    assertThat(invocation.name()).isEqualTo("review");
    assertThat(invocation.arguments()).containsExactly("security pass", "--fix");
}

@Test
void rejectsUnknownSyntaxAndEmptyCommand() {
    SlashCommandParser parser = new SlashCommandParser();
    assertThatThrownBy(() -> parser.parse("/"))
            .isInstanceOf(CommandParseException.class);
    assertThatThrownBy(() -> parser.parse("review"))
            .isInstanceOf(CommandParseException.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core -Dtest=SlashCommandParserTest test`

Expected: FAIL because `com.agent.core.command` types do not exist.

- [ ] **Step 3: Write minimal implementation**

Implement immutable records with exact nonblank validation. `SlashCommandParser` accepts a leading `/`, splits ASCII whitespace outside double quotes, rejects unterminated quotes, returns lower-case-free exact text (the caller must use the supplied name exactly), and never performs fuzzy matching.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-core -Dtest=SlashCommandParserTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-core/src/main/java/com/agent/core/command agent-core/src/test/java/com/agent/core/command/SlashCommandParserTest.java
git commit -m "feat(command): add slash command contracts and parser"
```

### Task 2: Registry, aliases, source precedence, and dispatcher

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/command/CommandHandler.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandResult.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandRegistry.java`
- Create: `agent-core/src/main/java/com/agent/core/command/InMemoryCommandRegistry.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandAuthorizationPolicy.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandAuthorizationDecision.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandAuditSink.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandAuditEvent.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandDispatcher.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandDispatchException.java`
- Test: `agent-core/src/test/java/com/agent/core/command/InMemoryCommandRegistryTest.java`
- Test: `agent-core/src/test/java/com/agent/core/command/CommandDispatcherTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void workspaceDefinitionOverridesGlobalDefinitionAndAliasesResolve() {
    InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
    registry.replace(List.of(definition("deploy", CommandSource.GLOBAL),
            definition("deploy", CommandSource.WORKSPACE)));
    assertThat(registry.find("deploy").orElseThrow().source())
            .isEqualTo(CommandSource.WORKSPACE);
    assertThat(registry.find("ship").orElseThrow().name()).isEqualTo("deploy");
}

@Test
void systemResultDoesNotInvokeWorkflowBridge() {
    AtomicInteger invocations = new AtomicInteger();
    CommandDefinition definition = definition("help", CommandSource.BUILT_IN)
            .withChannel(CommandChannel.SYSTEM_DIRECTIVE)
            .withHandler(invocation -> {
                invocations.incrementAndGet();
                return CommandResult.success("ok");
            });
    CommandDispatcher dispatcher = dispatcher(List.of(definition));
    assertThat(dispatcher.dispatch("/help", context()).status())
            .isEqualTo(CommandResult.Status.COMPLETED);
    assertThat(invocations).hasValue(1);
}

@Test
void unknownCommandAndDeniedCommandDoNotInvokeHandler() {
    AtomicInteger invocations = new AtomicInteger();
    CommandDefinition definition = definition("plan", CommandSource.BUILT_IN)
            .withHandler(invocation -> {
                invocations.incrementAndGet();
                return CommandResult.success("unexpected");
            });
    CommandDispatcher dispatcher = dispatcherWithDeniedPlan(List.of(definition));
    assertThat(dispatcher.dispatch("/missing", context()).status())
            .isEqualTo(CommandResult.Status.NOT_FOUND);
    assertThat(dispatcher.dispatch("/plan", context()).status())
            .isEqualTo(CommandResult.Status.DENIED);
    assertThat(invocations).hasValue(0);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-core -Dtest=InMemoryCommandRegistryTest,CommandDispatcherTest test`

Expected: FAIL because Registry and Dispatcher are missing.

- [ ] **Step 3: Write minimal implementation**

Use a read/write lock and monotonically increasing revision. Resolve exact names and aliases from a frozen snapshot; merge precedence is `BUILT_IN < GLOBAL < WORKSPACE`. `CommandDispatcher` performs parse → exact lookup → authorization → handler execution → audit and returns a `CommandResult`; no fuzzy execution path exists.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl agent-core -Dtest=InMemoryCommandRegistryTest,CommandDispatcherTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-core/src/main/java/com/agent/core/command agent-core/src/test/java/com/agent/core/command
git commit -m "feat(command): add registry and dual-channel dispatcher"
```

### Task 3: Deterministic system handlers and workflow bridge

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/command/SystemCommandHandlers.java`
- Create: `agent-core/src/main/java/com/agent/core/command/WorkflowCommandHandler.java`
- Create: `agent-core/src/main/java/com/agent/core/command/WorkflowCommandBridge.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandContextService.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandCheckpointService.java`
- Test: `agent-core/src/test/java/com/agent/core/command/SystemCommandHandlersTest.java`
- Test: `agent-core/src/test/java/com/agent/core/command/WorkflowCommandHandlerTest.java`

- [ ] **Step 1: Write failing tests**

Cover `/help`, `/context`, `/compact`, `/clear`, `/cost`, `/permissions`, and `/rewind`. The local test uses a counting `WorkflowCommandBridge` and counting LLM observer; every system command must leave both counters at zero. The workflow test asserts `/plan` renders `${request}` and calls the bridge exactly once.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-core -Dtest=SystemCommandHandlersTest,WorkflowCommandHandlerTest test`

Expected: FAIL because handlers and bridge are missing.

- [ ] **Step 3: Write minimal implementation**

`SystemCommandHandlers` receives ports for registry snapshot, token estimation, deterministic history summarization, conversation creation, permission policy, usage lookup and checkpoint restore. `/clear` calls the conversation creation port for the same workspace; it never deletes or archives the old conversation. `/rewind` requires an exact checkpoint reference and delegates authorization before restore. `WorkflowCommandHandler` only renders a validated template and calls `WorkflowCommandBridge`; it does not access `LlmClient` directly.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl agent-core -Dtest=SystemCommandHandlersTest,WorkflowCommandHandlerTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-core/src/main/java/com/agent/core/command agent-core/src/test/java/com/agent/core/command
git commit -m "feat(command): add local system and workflow handlers"
```

### Task 4: Secure Markdown command loader

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/command/MarkdownCommandLoader.java`
- Create: `agent-core/src/main/java/com/agent/core/command/MarkdownCommandLoadException.java`
- Create: `agent-core/src/main/java/com/agent/core/command/CommandTemplateRenderer.java`
- Modify: `agent-core/pom.xml` (add `jackson-dataformat-yaml`)
- Test: `agent-core/src/test/java/com/agent/core/command/MarkdownCommandLoaderTest.java`

- [ ] **Step 1: Write failing tests**

Use temporary real directories and files to verify: valid front matter loads; workspace overrides global; duplicate names in one source reject the batch; missing required front matter, unknown template variables, oversized files, path traversal and symbolic-link escape are rejected. The valid fixture is:

```text
---
name: plan
description: Plan a change
channel: WORKFLOW_SKILL
aliases: [roadmap]
arguments:
  - name: request
    required: true
permission: OPERATOR
---
Create an implementation plan for ${request} in ${workspacePath}.
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core -Dtest=MarkdownCommandLoaderTest test`

Expected: FAIL because loader and renderer are missing.

- [ ] **Step 3: Write minimal implementation**

Add `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` to `agent-core/pom.xml`. Parse the first and last `---` front matter delimiters with `ObjectMapper(new YAMLFactory())`. Validate exact field names, command name syntax, enum values and `${...}` variables. Resolve each configured root with `toRealPath`, enumerate regular files only, enforce a configured byte limit, and reject any file whose real path is outside its root. Return definitions tagged with `CommandSource.GLOBAL` or `CommandSource.WORKSPACE`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-core -Dtest=MarkdownCommandLoaderTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-core/src/main/java/com/agent/core/command agent-core/src/test/java/com/agent/core/command/MarkdownCommandLoaderTest.java
git commit -m "feat(command): load secure markdown commands"
```

### Task 5: Web configuration, registry endpoint, and conversation bridge

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/config/CommandProperties.java`
- Create: `agent-web/src/main/java/com/agent/web/command/CommandRegistryConfiguration.java`
- Create: `agent-web/src/main/java/com/agent/web/command/ConversationWorkflowCommandBridge.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/CommandController.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/CommandView.java`
- Create: `agent-web/src/main/java/com/agent/web/controller/CommandInvocationRequest.java`
- Modify: `agent-web/src/main/resources/application.properties`
- Test: `agent-web/src/test/java/com/agent/web/controller/CommandControllerTest.java`
- Test: `agent-web/src/test/java/com/agent/web/command/ConversationWorkflowCommandBridgeTest.java`

- [ ] **Step 1: Write failing tests**

Assert `GET /api/workspaces/{workspaceId}/commands` returns the live Registry revision, channel, source, aliases and parameter schema. Submit `POST /api/workspaces/{workspaceId}/commands` with `{"input":"/plan fix login"}` and assert the workflow bridge receives `plan` plus `fix login`; submit the same request against a workspace without operator permission and assert HTTP 403 with no bridge invocation.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-web -Dtest=CommandControllerTest,ConversationWorkflowCommandBridgeTest test`

Expected: FAIL because endpoint and configuration do not exist.

- [ ] **Step 3: Write minimal implementation**

Bind `agent.commands.global-directory` and `agent.commands.max-file-bytes` through `@ConfigurationProperties`. Load `.agent/commands` under the exact workspace root obtained from `WorkspaceAccessService`. `CommandInvocationRequest` contains only the exact `input` string and optional `modelGroupId`; the bridge adapts the rendered command to `ConversationService.submitTurn` using the existing reviewer/model/orchestration inputs and records command metadata in the existing audit sink.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl agent-web -Dtest=CommandControllerTest,ConversationWorkflowCommandBridgeTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-web/src/main/java agent-web/src/main/resources/application.properties agent-web/src/test/java/com/agent/web/controller/CommandControllerTest.java agent-web/src/test/java/com/agent/web/command/ConversationWorkflowCommandBridgeTest.java
git commit -m "feat(web): expose governed slash command registry"
```

### Task 6: Frontend registry-backed slash menu and workflow forms

**Files:**
- Create: `agent-web/src/main/frontend/src/api/commandApi.ts`
- Create: `agent-web/src/main/frontend/src/api/commandApi.test.ts`
- Modify: `agent-web/src/main/frontend/src/components/ConversationComposer.tsx`
- Modify: `agent-web/src/main/frontend/src/components/ConversationComposer.test.tsx`
- Modify: `agent-web/src/main/frontend/src/api/contracts.ts`
- Modify: `agent-web/src/main/frontend/src/styles.css`

- [ ] **Step 1: Write failing tests**

Assert the composer loads `/api/workspaces/ws-1/commands` when input is `/`, renders service-provided `/help` and `/plan` entries, shows workflow parameters after selection, and does not call ordinary `submitConversationTurn` for a system command. The fixture response contains `{revision: 3, commands: [{name: "help", channel: "SYSTEM_DIRECTIVE", source: "BUILT_IN", parameters: []}, {name: "plan", channel: "WORKFLOW_SKILL", source: "GLOBAL", parameters: [{name: "request", required: true}]}]}`. Assert a missing command result shows the server error.

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm --prefix agent-web/src/main/frontend run test:run -- src/components/ConversationComposer.test.tsx src/api/commandApi.test.ts`

Expected: FAIL because command API and registry-backed rendering are missing.

- [ ] **Step 3: Write minimal implementation**

Add strict response decoding in `commandApi.ts`. Replace the local CLI-only slash list with a separate command registry request, preserve the existing governed CLI selector as its own entry point, and submit system commands to the command endpoint. Render parameter metadata from the response, with keyboard navigation and accessible listbox semantics.

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm --prefix agent-web/src/main/frontend run test:run -- src/components/ConversationComposer.test.tsx src/api/commandApi.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-web/src/main/frontend/src
git commit -m "feat(frontend): use live slash command registry"
```

### Task 7: Full verification and real request interception evidence

**Files:**
- Create: `agent-core/src/test/java/com/agent/core/command/CommandEngineEggTest.java`
- Create: `agent-web/src/test/java/com/agent/web/command/CommandEngineEddTest.java`
- Create: `docs/superpowers/evidence/2026-08-14-claude-code-command-engine-verification.md`

- [ ] **Step 1: Write failing integration tests**

Use an in-process HTTP server and a real `LlmClient`/existing graph wiring. Submit `/context` and `/help` and assert request count remains zero. Submit `/plan` and assert the request count is positive, command metadata appears in Trace/Audit, and the resulting turn reaches a terminal state.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-core,agent-web -Dtest=CommandEngineEggTest,CommandEngineEddTest test`

Expected: FAIL until all Web wiring and command paths are connected.

- [ ] **Step 3: Implement only test harness adapters**

Reuse existing HTTP capture, Trace and audit utilities; do not replace real LLM calls with a fake success response. Keep credentials outside the repository and mark unavailable external services as an explicit blocked verification item.

- [ ] **Step 4: Run the complete verification suite**

Run: `mvn test` and `npm --prefix agent-web/src/main/frontend run test:run`.

Expected: all existing and new tests pass; EGG/EDD evidence records separate local/system and workflow request counts.

- [ ] **Step 5: Commit**

```bash
git add agent-core/src/test agent-web/src/test docs/superpowers/evidence/2026-08-14-claude-code-command-engine-verification.md
git commit -m "test(command): verify local interception and workflow dispatch"
```
