# Governed CLI Agent Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect governed code tools, knowledge evidence, structured CLI authorization, and repair-loop EDD to the production Coder -> Ops chain.

**Architecture:** A built-in `code.apply-diff` `ToolDefinition` binds the workspace from `ToolInvocationContext` and delegates to `AstService`. `CoderNode` consumes Planner knowledge evidence and emits a strict command name/argument array. A catalog-backed interrupt policy authorizes the command before Ops; Ops executes only the policy-approved plan and records authorization plus terminal evidence.

**Tech Stack:** Java 21 records, existing ToolRegistry/HarnessToolExecutor, CliCommandCatalog/GovernedCliCommandExecutor, JGit/pty4j, Spring configuration, JUnit 5, MockRestServiceServer.

---

### Task 1: Built-in code patch tool

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/tool/builtin/CodePatchTool.java`
- Test: `agent-core/src/test/java/com/agent/core/tool/builtin/CodePatchToolTest.java`

- [ ] **Step 1: Write the failing test** for exact tool name/schema, real temp Git diff application, workspace-bound context, and conflict error stack.
- [ ] **Step 2: Run `mvn -pl agent-core -Dtest=CodePatchToolTest test`** with JDK 21 and confirm missing type failure.
- [ ] **Step 3: Implement the definition factory and handler** with strict JSON Schema and relative updated-file output.
- [ ] **Step 4: Rerun the focused test and confirm green.**
- [ ] **Step 5: Commit `feat(tool): add governed code patch tool`.**

### Task 2: Coder structured command and ToolRegistry integration

**Files:**
- Modify: `agent-core/src/main/java/com/agent/core/nodes/CoderNode.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/OpsNode.java`
- Test: `agent-core/src/test/java/com/agent/core/nodes/CoderNodeTest.java`

- [ ] **Step 1: Add failing tests** for strict `commandName`/`commandArguments`, Planner knowledge injection/fingerprint, registry invocation, and rejection of legacy raw command JSON.
- [ ] **Step 2: Run focused Coder tests and confirm the expected protocol/tool failure.**
- [ ] **Step 3: Add production constructor injection, tool invocation context, strict command parsing, and state evidence keys while preserving the legacy direct-AST constructor.**
- [ ] **Step 4: Rerun focused Coder tests and the existing node regression suite.**
- [ ] **Step 5: Commit `feat(coder): route code changes through governed tools`.**

### Task 3: Catalog-backed CLI approval policy

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/cli/CliApprovalInterruptPolicy.java`
- Modify: `agent-core/src/main/java/com/agent/core/nodes/OpsNode.java`
- Test: `agent-core/src/test/java/com/agent/core/cli/CliApprovalInterruptPolicyTest.java`
- Test: `agent-core/src/test/java/com/agent/core/nodes/OpsNodeTest.java`

- [ ] **Step 1: Write failing policy/Ops tests** for read-only allow, mutating approval interrupt, argument injection rejection, exact details, and zero terminal calls on denied/approval routes.
- [ ] **Step 2: Run focused tests and verify red.**
- [ ] **Step 3: Implement exact state parsing, policy decisions, approved plan execution, and authorization/result state writes.**
- [ ] **Step 4: Rerun policy/Ops tests and existing CLI governance tests.**
- [ ] **Step 5: Commit `feat(ops): enforce catalog authorization before terminal execution`.**

### Task 4: Production graph wiring

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`
- Test: `agent-web/src/test/java/com/agent/web/config/ProductionCodeAgentIntegrationTest.java`

- [ ] **Step 1: Extend the integration test** to require the built-in patch tool, exact CLI catalog, and policy-bound production graph.
- [ ] **Step 2: Run it and confirm missing wiring fails.**
- [ ] **Step 3: Register `code.apply-diff`, declare exact read-only test commands, inject the registry/catalog/policy into the graph, and keep sample graphs unchanged.**
- [ ] **Step 4: Rerun the integration test and verify no raw shell command reaches production Coder.**
- [ ] **Step 5: Commit `feat(web): wire governed cli agent production graph`.**

### Task 5: Real repository repair-loop EDD

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/CliAgentWorkflowEddTest.java`
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] **Step 1: Write the EDD scenario/report assertions** for real temporary Git, PTY output, first failed command, second repair attempt, exact trace order, and fixed report fields.
- [ ] **Step 2: Run the EDD and observe its red state before the production wiring is complete.**
- [ ] **Step 3: Connect the deterministic model responses and real workspace loop until the EDD passes.**
- [ ] **Step 4: Record the 7A pitfall and evidence in `ENGINEERING_PITFALLS.md`.**
- [ ] **Step 5: Commit `test(eval): add cli agent repair workflow edd`.**

### Task 6: Full verification and merge

- [ ] **Step 1: Run `mvn -pl agent-core,agent-web,agent-eval -am test` with JDK 21.**
- [ ] **Step 2: Run `mvn clean package -DskipTests -Dfrontend.skip=true`.**
- [ ] **Step 3: Run `git diff --check`, inspect status and forbidden files, then fast-forward merge to `master`.**
- [ ] **Step 4: Re-run focused profile/CLI/EDD checks on merged `master`.**
- [ ] **Step 5: Remove the 7A worktree and delete the merged branch.**

