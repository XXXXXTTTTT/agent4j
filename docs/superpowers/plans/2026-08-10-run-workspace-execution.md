# Run Workspace Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每个持久化会话轮次的 Coder、CLI 授权和 Ops 节点使用同一个精确工作区目录。

**Architecture:** 在 `agent-core` 增加按路径解析 `TerminalTarget` 的函数式端口，保留旧的固定目标构造器兼容现有调用方。生产 Web 配置以挂载根为边界创建 resolver；Docker 目标只携带规范化相对路径，PTY 目标直接使用本轮真实路径。

**Tech Stack:** Java 21 records/sealed types, JUnit 5, Docker-Java target records, Spring Boot configuration properties.

---

### Task 1: Add Exact Workspace Target Resolver Contract

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/cli/WorkspaceTerminalTargetResolver.java`
- Modify: `agent-core/src/main/java/com/agent/core/cli/CliApprovalInterruptPolicy.java`
- Test: `agent-core/src/test/java/com/agent/core/cli/CliApprovalInterruptPolicyTest.java`

- [ ] **Step 1: Write the failing test**

Create two exact `coder.workspacePath` states and a resolver recording the path. Assert `authorizeForExecution` resolves the exact path and writes the same path into `CliCommandIntent.workspace()` and its `CommandRequest.target()`.

```java
@Test
void resolvesTerminalTargetFromTheExactWorkspaceStateKey() {
    List<Path> resolved = new ArrayList<>();
    WorkspaceTerminalTargetResolver resolver = path -> {
        resolved.add(path);
        return new PtyTarget(bash, path);
    };
    CliApprovalInterruptPolicy policy = new CliApprovalInterruptPolicy(
            catalog, resolver, Duration.ofSeconds(10), objectMapper);
    CliAuthorization authorization = policy.authorizeForExecution(stateFor(workspaceA), true);

    assertThat(resolved).containsExactly(workspaceA.toRealPath());
    assertThat(authorization.plan().request().target())
            .isEqualTo(new PtyTarget(bash, workspaceA.toRealPath()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core -am -Dtest=CliApprovalInterruptPolicyTest test`

Expected: compilation fails because `WorkspaceTerminalTargetResolver` and its constructor do not exist.

- [ ] **Step 3: Write the minimal implementation**

Create:

```java
@FunctionalInterface
public interface WorkspaceTerminalTargetResolver {
    TerminalTarget resolve(Path workspacePath);
}
```

Change `CliApprovalInterruptPolicy` to store the resolver, add a resolver constructor, and keep the existing fixed-target constructor by delegating with `ignored -> target`. In `parse`, resolve `Path.of(requireVariable(state, CoderNode.WORKSPACE_PATH_KEY))` once and pass both the path and `resolver.resolve(path)` to `CliCommandIntent`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-core -am -Dtest=CliApprovalInterruptPolicyTest test`

Expected: all CLI policy tests pass.

- [ ] **Step 5: Commit**

```powershell
git add agent-core/src/main/java/com/agent/core/cli/WorkspaceTerminalTargetResolver.java agent-core/src/main/java/com/agent/core/cli/CliApprovalInterruptPolicy.java agent-core/src/test/java/com/agent/core/cli/CliApprovalInterruptPolicyTest.java
git commit -m "fix(workspace): resolve CLI targets from run workspace"
```

### Task 2: Support Safe Docker Subdirectory Targets

**Files:**
- Modify: `agent-sandbox/src/main/java/com/agent/sandbox/pty/DockerTarget.java`
- Modify: `agent-sandbox/src/main/java/com/agent/sandbox/docker/DockerCommandExecutor.java`
- Test: `agent-sandbox/src/test/java/com/agent/sandbox/docker/DockerCommandExecutorTest.java`

- [ ] **Step 1: Write the failing test**

Add tests for `ContainerWorkspaceSource("agent4j-web-local", "/agent-workspace")` defaulting to an empty relative path, accepting `modules/app`, and rejecting `/tmp/app`, `../app`, and `modules/../app`.

```java
@Test
void acceptsOnlyNormalizedRelativeWorkspacePaths() {
    assertThat(new DockerTarget.ContainerWorkspaceSource(
            "web", "/agent-workspace", "modules/app").relativePath())
            .isEqualTo("modules/app");
    assertThatIllegalArgumentException().isThrownBy(() ->
            new DockerTarget.ContainerWorkspaceSource("web", "/agent-workspace", "../app"));
    assertThatIllegalArgumentException().isThrownBy(() ->
            new DockerTarget.ContainerWorkspaceSource("web", "/agent-workspace", "/tmp/app"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-sandbox -am -Dtest=DockerCommandExecutorTest test`

Expected: compilation fails because the three-argument source constructor and `relativePath()` do not exist.

- [ ] **Step 3: Write the minimal implementation**

Add `String relativePath` to `ContainerWorkspaceSource`; keep its two-argument constructor delegating with `""`. Normalize `\\` to `/`, reject leading `/` and every `..` segment, and preserve exact segment spelling. In `DockerCommandExecutor.bindSource`, resolve the source bind root and append the relative path; reject a missing child directory.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-sandbox -am -Dtest=DockerCommandExecutorTest test`

Expected: all sandbox mount and cleanup tests pass.

- [ ] **Step 5: Commit**

```powershell
git add agent-sandbox/src/main/java/com/agent/sandbox/pty/DockerTarget.java agent-sandbox/src/main/java/com/agent/sandbox/docker/DockerCommandExecutor.java agent-sandbox/src/test/java/com/agent/sandbox/docker/DockerCommandExecutorTest.java
git commit -m "fix(workspace): support safe Docker workspace subdirectories"
```

### Task 3: Wire Production Resolver Into the Graph

**Files:**
- Modify: `agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java`
- Test: `agent-web/src/test/java/com/agent/web/config/ProductionGraphConfigurationTest.java`
- Test: `agent-web/src/test/java/com/agent/web/config/ProductionCodeAgentIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Add PTY and Docker resolver tests. For PTY, two child directories under the configured root must produce two `PtyTarget.workingDirectory()` values. For Docker, the child must produce the configured container workspace and a source relative path; a root escape must throw `IllegalArgumentException`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-web -am -Dfrontend.skip=true -Dtest=ProductionGraphConfigurationTest,ProductionCodeAgentIntegrationTest test`

Expected: failure because production exposes only one startup-bound `TerminalTarget`.

- [ ] **Step 3: Write the minimal implementation**

Add package-visible `workspaceTargetResolver(ProductionAgentProperties)`. It must call `toRealPath()`, require the result to start with the configured root real path, create a `PtyTarget` for `PTY`, and create a `DockerTarget` with a normalized relative source path for `DOCKER`. Pass this resolver to `CliApprovalInterruptPolicy`; keep the exact state key `coder.workspacePath` and the graph bean name `code-agent` unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-web -am -Dfrontend.skip=true -Dtest=ProductionGraphConfigurationTest,ProductionCodeAgentIntegrationTest test`

Expected: PASS with both execution modes and root escape checks.

- [ ] **Step 5: Commit**

```powershell
git add agent-web/src/main/java/com/agent/web/config/ProductionGraphConfiguration.java agent-web/src/test/java/com/agent/web/config/ProductionGraphConfigurationTest.java agent-web/src/test/java/com/agent/web/config/ProductionCodeAgentIntegrationTest.java
git commit -m "fix(workspace): bind production graph to each run directory"
```

### Task 4: Verification Gate

**Files:**
- No source changes.

- [ ] **Step 1: Run Java tests**

Run: `mvn -pl agent-core,agent-sandbox,agent-web -am -Dfrontend.skip=true test`

Expected: zero failures and zero errors.

- [ ] **Step 2: Build the application**

Run: `mvn -pl agent-web -am -Dfrontend.skip=true -DskipTests package`

Expected: `BUILD SUCCESS` and `agent-web/target/agent-web-0.1.0-SNAPSHOT.jar` exists.

- [ ] **Step 3: Run frontend checks**

Run from `agent-web/src/main/frontend`: `npm run test:run` and `npm run build`.

Expected: both commands exit 0.

- [ ] **Step 4: Check the worktree**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors and only intentionally staged changes remain.
