# Controlled Agent Profile Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose immutable, constructor-injected Agent Profile metadata and read-only graph topology queries without allowing dynamic graph editing or execution.

**Architecture:** `agent-core` owns validated `AgentProfile`, exact `AgentProfileRegistry`, and immutable snapshot types. `agent-web` adapts the registry to three GET endpoints and existing ProblemDetail errors. Spring configurations declare profile beans alongside their exact graph beans; the registry creates a graph only for detail/topology inspection and closes it immediately.

**Tech Stack:** Java 21 records, existing `GraphRegistry`/`StateGraph`, Spring WebFlux, JUnit 5, WebTestClient.

---

### Task 1: Core profile protocol and exact registry

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/profile/AgentProfile.java`
- Create: `agent-core/src/main/java/com/agent/core/profile/AgentProfileSnapshot.java`
- Create: `agent-core/src/main/java/com/agent/core/profile/AgentProfileNotFoundException.java`
- Create: `agent-core/src/main/java/com/agent/core/profile/AgentProfileRegistry.java`
- Test: `agent-core/src/test/java/com/agent/core/profile/AgentProfileRegistryTest.java`

- [ ] **Step 1: Write the failing test** for immutable validation, exact lookup, stable IDs, one graph creation, topology inspection without node execution, and unknown profile/graph errors.
- [ ] **Step 2: Run the focused test** with `mvn -pl agent-core -Dtest=AgentProfileRegistryTest test`; confirm failure because the profile types do not exist.
- [ ] **Step 3: Implement minimal records, exception, and registry** using defensive copies, exact strings, and try-with-resources around `GraphRegistry.create()`.
- [ ] **Step 4: Run the focused test** and confirm all assertions pass.
- [ ] **Step 5: Commit** with `feat(core): add controlled agent profile registry`.

### Task 2: Register profile metadata with existing graphs

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/config/HarnessConfiguration.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/SampleGraphConfiguration.java`
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Test: `agent-web/src/test/java/com/agent/web/AgentWebApplicationTest.java`

- [ ] **Step 1: Extend the application-context test** to require `AgentProfileRegistry` and an exact `demo-agent` profile when the sample graph is enabled.
- [ ] **Step 2: Run the focused test** and confirm the context fails because no registry/profile bean exists.
- [ ] **Step 3: Add constructor-injected profile beans and the registry bean**; profile metadata must use the exact existing graph IDs and existing `ExecutionBudget`/`TaskType` values.
- [ ] **Step 4: Run the focused context test** and confirm it passes without enabling production model dependencies.
- [ ] **Step 5: Commit** with `feat(web): register controlled agent profiles`.

### Task 3: Read-only profile HTTP views and controller

**Files:**
- Create: `agent-web/src/main/java/com/agent/web/profile/AgentProfileView.java`
- Create: `agent-web/src/main/java/com/agent/web/profile/AgentProfileDetailView.java`
- Create: `agent-web/src/main/java/com/agent/web/profile/AgentProfileController.java`
- Modify: `agent-web/src/main/java/com/agent/web/controller/RunExceptionHandler.java`
- Test: `agent-web/src/test/java/com/agent/web/profile/AgentProfileControllerTest.java`

- [ ] **Step 1: Write WebFlux tests** for list, detail, topology, exact path handling, 404 profile, and 404 graph responses.
- [ ] **Step 2: Run the focused controller test** and confirm failure because routes and views do not exist.
- [ ] **Step 3: Implement immutable views and GET-only controller**; list must call `profileIds`/`get`, detail must call `inspect`, topology must return the snapshot topology.
- [ ] **Step 4: Add `AgentProfileNotFoundException` to the existing 404 handler** and rerun focused tests.
- [ ] **Step 5: Commit** with `feat(web): expose agent profile topology queries`.

### Task 4: Engineering pitfalls and regression verification

**Files:**
- Modify: `docs/ENGINEERING_PITFALLS.md`

- [ ] **Step 1: Add the 6B pitfall entry** explaining why unrestricted low-code/class-name graph configuration bypasses type and permission gates, and why the profile layer is read-only.
- [ ] **Step 2: Run core and web regression tests** with `mvn -pl agent-core,agent-web -am test -Dfrontend.skip=true`.
- [ ] **Step 3: Run the clean package gate** with `mvn clean package -DskipTests -Dfrontend.skip=true`.
- [ ] **Step 4: Run `git diff --check` and inspect `git status`** to ensure no logs or build artifacts are staged.
- [ ] **Step 5: Commit** with `docs(knowledge): record controlled profile boundary`.

### Task 5: Merge to master

- [ ] **Step 1: Verify the feature branch log and clean status.**
- [ ] **Step 2: Fast-forward merge `feat/guide-sixth-part-6b` into local `master`.**
- [ ] **Step 3: Delete the merged branch and worktree only after the merge succeeds.**
- [ ] **Step 4: Run the final verification command from `master`.**

