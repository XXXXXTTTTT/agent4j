# 4D CLI Capability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 PTY/Docker 沙箱上增加结构化 CLI 命令目录、风险审批、工作区边界和安全异步执行门面。

**Architecture:** `com.agent.core.cli` 保存不可变命令定义和意图，`CliCommandCatalog` 只生成经过 token 校验、real path 校验和审批决策的 `CliCommandPlan`。`GovernedCliCommandExecutor` 只把 ALLOWED 计划交给注入的 `TerminalCommandExecutor`；拒绝与待审批不启动进程，既有 `SandboxTerminalService` 继续负责 Bash、Docker、PTY、超时和 ANSI 日志。

**Tech Stack:** Java 21 records/sealed types、现有 `agent-core`、`agent-sandbox`、Jackson-free SHA-256、JUnit 5、AssertJ、agent-eval EDD。

---

## 文件结构

- Create: `agent-core/src/main/java/com/agent/core/cli/CliRiskLevel.java` — 三档命令风险。
- Create: `agent-core/src/main/java/com/agent/core/cli/CliAuthorizationDecision.java` — 授权结果。
- Create: `agent-core/src/main/java/com/agent/core/cli/CliAuthorizationContext.java` — 能力和双层审批上下文。
- Create: `agent-core/src/main/java/com/agent/core/cli/CliCommandDefinition.java` — 可执行文件、固定参数、风险与能力定义。
- Create: `agent-core/src/main/java/com/agent/core/cli/CliCommandIntent.java` — 用户/模型结构化命令意图。
- Create: `agent-core/src/main/java/com/agent/core/cli/CliCommandPlan.java` — 安全渲染后的内部 CommandRequest 和指纹。
- Create: `agent-core/src/main/java/com/agent/core/cli/CliAuthorization.java` — 决策、原因和计划。
- Create: `agent-core/src/main/java/com/agent/core/cli/CliExecutionResult.java` — 决策与可选终端结果。
- Create: `agent-core/src/main/java/com/agent/core/cli/CliCommandDefinitionException.java`、`CliCommandNotFoundException.java`、`CliArgumentException.java`、`CliWorkspaceViolationException.java` — 精确异常。
- Create: `agent-core/src/main/java/com/agent/core/cli/CliCommandCatalog.java` — 目录快照、参数渲染和授权。
- Create: `agent-core/src/main/java/com/agent/core/cli/GovernedCliCommandExecutor.java` — 终端服务适配门面。
- Create: `agent-core/src/test/java/com/agent/core/cli/CliCommandDefinitionTest.java` — 定义边界。
- Create: `agent-core/src/test/java/com/agent/core/cli/CliCommandCatalogTest.java`、`CliCommandRenderingTest.java` — 授权、边界、渲染。
- Create: `agent-core/src/test/java/com/agent/core/cli/GovernedCliCommandExecutorTest.java` — 调用计数和结果保留。
- Create: `agent-core/src/test/java/com/agent/core/cli/GovernedCliPtyIntegrationTest.java` — 真实 Git Bash PTY。
- Create: `agent-eval/src/test/java/com/agent/eval/CliCapabilityEddTest.java` — 七条确定性 EDD 场景。
- Modify: `docs/ENGINEERING_PITFALLS.md` — 追加 4D 复盘。

### Task 1: 领域记录与精确异常

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/cli/CliRiskLevel.java`, `CliAuthorizationDecision.java`, `CliAuthorizationContext.java`, `CliCommandDefinition.java`, `CliCommandIntent.java`, `CliCommandPlan.java`, `CliAuthorization.java`, `CliExecutionResult.java` 及四个异常。
- Test: `agent-core/src/test/java/com/agent/core/cli/CliCommandDefinitionTest.java`。

- [ ] **Step 1: Write the failing test**

```java
@Test
void rejectsShellControlTokensAndFreezesDefinitionInputs() {
    assertThatThrownBy(() -> new CliCommandDefinition(
            "maven", "mvn", List.of("test;rm"), CliRiskLevel.READ_ONLY, Set.of()))
            .isInstanceOf(IllegalArgumentException.class);
    List<String> fixed = new ArrayList<>(List.of("test"));
    CliCommandDefinition definition = new CliCommandDefinition(
            "maven", "mvn", fixed, CliRiskLevel.MUTATING, Set.of());
    fixed.add("package");
    assertThat(definition.fixedArguments()).containsExactly("test");
    assertThatThrownBy(() -> definition.fixedArguments().add("package"))
            .isInstanceOf(UnsupportedOperationException.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core '-Dtest=CliCommandDefinitionTest' test`

Expected: FAIL at test compilation because `com.agent.core.cli` does not exist.

- [ ] **Step 3: Write minimal implementation**

实现上述 records：命令名使用 `[a-z][a-z0-9_.-]{0,63}`；executable 和固定参数使用统一
token 门禁；集合使用 `List.copyOf`/`Set.copyOf`；`CliCommandIntent` 校验目录、目标、超时；
`CliCommandPlan` 校验 commandSha256 为 64 位小写十六进制；授权与执行结果冻结 Optional。
异常分别保存命令名、路径或原始 cause，不把异常 message 当作唯一证据。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl agent-core '-Dtest=CliCommandDefinitionTest' test`

Expected: PASS。

- [ ] **Step 5: Commit**

```text
feat(cli): define structured command domain
```

### Task 2: 目录授权、工作区边界与 Shell 渲染

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/cli/CliCommandCatalog.java`。
- Test: `agent-core/src/test/java/com/agent/core/cli/CliCommandCatalogTest.java`, `CliCommandRenderingTest.java`。

- [ ] **Step 1: Write the failing tests**

```java
@Test
void requiresApprovalByRiskAndRejectsRealPathEscape() throws IOException {
    CliCommandCatalog catalog = new CliCommandCatalog(List.of(
            new CliCommandDefinition("read", "printf", List.of(), CliRiskLevel.READ_ONLY, Set.of()),
            new CliCommandDefinition("write", "printf", List.of(), CliRiskLevel.MUTATING, Set.of()),
            new CliCommandDefinition("destroy", "printf", List.of(), CliRiskLevel.DESTRUCTIVE, Set.of())));
    CliAuthorization waiting = catalog.authorize(intent("write", root), context(false, false));
    assertThat(waiting.decision()).isEqualTo(CliAuthorizationDecision.APPROVAL_REQUIRED);
    assertThatThrownBy(() -> catalog.authorize(intent("read", root.resolve("..")), context(true, true)))
            .isInstanceOf(CliWorkspaceViolationException.class);
}

@Test
void quotesArgumentsAndRejectsOperators() {
    CliAuthorization allowed = catalog.authorize(intent("read", root, "hello world"), context(true, true));
    assertThat(allowed.plan().request().bashCommand()).contains("'hello world'");
    assertThatThrownBy(() -> catalog.authorize(intent("read", root, "ok;rm"), context(true, true)))
            .isInstanceOf(CliArgumentException.class);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-core '-Dtest=CliCommandCatalogTest,CliCommandRenderingTest' test`

Expected: FAIL because `CliCommandCatalog` and secure rendering are absent.

- [ ] **Step 3: Write minimal implementation**

`CliCommandCatalog` 构造时校验重复名称并发布 `Map.copyOf`；`authorize` 按“精确查找 → 参数
token → real path → 渲染指纹 → 能力 → 风险审批”顺序执行。目标路径使用 `switch` 精确处理
`PtyTarget.workingDirectory()` 与 `DockerTarget.hostWorkspace()`，任何其他类型无法编译进入
sealed switch。渲染器只允许定义 executable、fixedArguments 和 intent.arguments，token 使用
单引号安全引用；禁止 `\0`、控制符、换行、`;`, `&`, `|`, `<`, `>`, `` ` ``, `$`。

`READ_ONLY` 返回 `ALLOWED`；`MUTATING` 在 `userApproved=false` 返回 `APPROVAL_REQUIRED`；
`DESTRUCTIVE` 在任一审批标记为 false 时返回 `APPROVAL_REQUIRED`；能力缺失优先返回 `DENIED`。
`commandSha256` 对完整渲染字符串计算 SHA-256，计划内部构造现有 `CommandRequest`。

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl agent-core '-Dtest=CliCommandCatalogTest,CliCommandRenderingTest' test`

Expected: PASS，包含符号链接越界、自然名称查找、三档风险、能力拒绝和稳定指纹。

- [ ] **Step 5: Commit**

```text
feat(cli): enforce command policy and workspace boundaries
```

### Task 3: 治理执行门面与 PTY 集成

**Files:**
- Create: `agent-core/src/main/java/com/agent/core/cli/GovernedCliCommandExecutor.java`。
- Test: `agent-core/src/test/java/com/agent/core/cli/GovernedCliCommandExecutorTest.java`, `GovernedCliPtyIntegrationTest.java`。

- [ ] **Step 1: Write the failing tests**

```java
@Test
void deniedAndWaitingDecisionsNeverCallTerminal() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    TerminalCommandExecutor terminal = (request, logs) -> {
        calls.incrementAndGet();
        return CompletableFuture.completedFuture(new CommandResult(0, "ok", "", false));
    };
    GovernedCliCommandExecutor executor = new GovernedCliCommandExecutor(catalog, terminal);
    CliExecutionResult result = executor.execute(intent("write"), context(false, false), ignored -> { }).join();
    assertThat(result.authorization().decision()).isEqualTo(CliAuthorizationDecision.APPROVAL_REQUIRED);
    assertThat(result.result()).isEmpty();
    assertThat(calls).hasValue(0);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl agent-core '-Dtest=GovernedCliCommandExecutorTest' test`

Expected: FAIL because the governed executor is absent.

- [ ] **Step 3: Write minimal implementation**

`GovernedCliCommandExecutor.execute` 同步调用 catalog.authorize；非 `ALLOWED` 返回
`CompletableFuture.completedFuture(new CliExecutionResult(authorization, Optional.empty()))`；
允许时调用注入的 `TerminalCommandExecutor.execute(authorization.plan().request(), logConsumer)`，
并把 Future 结果包装为 `Optional.of(result)`。拒绝、待审批和能力错误不启动执行器；不吞掉
terminal Future 的异常。

`GovernedCliPtyIntegrationTest` 使用 `Path.of("D:/Git/bin/bash.exe")`，不存在时用 assumption
跳过；临时目录必须是 intent 的 workspaceRoot，实际命令使用目录内 `printf` 定义，断言输出、
PTY 日志和虚拟线程，不直接调用 `SandboxTerminalService` 的 raw CommandRequest API。

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl agent-core '-Dtest=GovernedCliCommandExecutorTest,GovernedCliPtyIntegrationTest' test`

Expected: fake executor 全部通过；有 Git Bash 时真实 PTY 通过，无 Bash 时只有明确 assumption skip。

- [ ] **Step 5: Commit**

```text
feat(cli): add governed terminal execution facade
```

### Task 4: EDD、工程复盘与里程碑验证

**Files:**
- Create: `agent-eval/src/test/java/com/agent/eval/CliCapabilityEddTest.java`。
- Modify: `docs/ENGINEERING_PITFALLS.md`，追加 4D 小节。

- [ ] **Step 1: Write the failing EDD test**

报告固定写入 `target/edd/cli-capability-edd.json`，每项字段严格为
`taskId/status/decision/commandSha256/exitCode/timedOut/terminalCalls/passed`。七个任务 ID 精确为
`cli.read-only`、`cli.mutating-approval`、`cli.destructive-admin`、`cli.capability-denied`、
`cli.argument-injection`、`cli.workspace-escape`、`cli.pty-output`；报告不得保存命令正文或用户参数。

- [ ] **Step 2: Run EDD to verify it fails**

Run: `mvn -pl agent-eval -am '-Dtest=CliCapabilityEddTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL at test compilation because `CliCapabilityEddTest` and the governed CLI API are absent.

- [ ] **Step 3: Write minimal EDD and review entry**

使用 fake `TerminalCommandExecutor` 记录 terminalCalls；前三个场景验证风险与审批，第四个验证
能力拒绝，第五个验证 Shell 控制符，第六个验证 `toRealPath` 越界，第七个在 Git Bash 存在时
执行真实 `printf`，不存在时用 JUnit assumption。复盘按“问题现象 → 根因分析 → 解决方案/代码级
实现 → 证据”记录 raw Shell 旁路、参数注入、符号链接逃逸和审批误执行。

- [ ] **Step 4: Run EDD to verify it passes**

Run: `mvn -pl agent-eval -am '-Dtest=CliCapabilityEddTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: 7 scenarios pass and report contains no command body.

- [ ] **Step 5: Commit**

```text
test(eval): add cli capability edd
docs(knowledge): record cli governance pitfalls
```

### Task 5: 全量验收

- [ ] **Step 1: Run focused and complete tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -pl agent-core '-Dtest=CliCommandDefinitionTest,CliCommandCatalogTest,CliCommandRenderingTest,GovernedCliCommandExecutorTest,GovernedCliPtyIntegrationTest' test
mvn -pl agent-core,agent-eval -am test
```

Expected: 0 failures/errors；真实 Git Bash/Docker 测试沿用现有 assumption 门禁。

- [ ] **Step 2: Run clean package and hygiene checks**

```powershell
mvn clean package '-DskipTests' '-Dfrontend.skip=true'
git -c safe.directory='D:/agent4j/.worktrees/guide-third-part-knowledge' diff --check
git -c safe.directory='D:/agent4j/.worktrees/guide-third-part-knowledge' status --short
```

Expected: 全模块打包成功，diff check 为空，状态只包含已提交变更；target、日志、`.env` 不进入 Git。
