package com.agent.eval;

import com.agent.core.cli.CliAuthorization;
import com.agent.core.cli.CliAuthorizationContext;
import com.agent.core.cli.CliAuthorizationDecision;
import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.cli.CliCommandIntent;
import com.agent.core.cli.CliRiskLevel;
import com.agent.core.cli.GovernedCliCommandExecutor;
import com.agent.core.intent.RequiredCapability;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.SandboxTerminalService;
import com.agent.sandbox.pty.Stream;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import com.agent.sandbox.pty.TerminalLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 对结构化 CLI 能力执行确定性端到端评测。 */
class CliCapabilityEddTest {

    private static final Path BASH_EXECUTABLE = Path.of("D:/Git/bin/bash.exe");
    private static final Set<String> REPORT_FIELDS = Set.of(
            "taskId", "status", "decision", "commandSha256", "exitCode", "timedOut",
            "terminalCalls", "passed");
    private static final List<String> TASK_IDS = List.of(
            "cli.read-only",
            "cli.mutating-approval",
            "cli.destructive-admin",
            "cli.capability-denied",
            "cli.argument-injection",
            "cli.workspace-escape",
            "cli.pty-output");

    @TempDir
    Path workspace;

    @Test
    void evaluatesGovernedCliScenariosAndWritesAuditableReport() throws Exception {
        assumeTrue(Files.isRegularFile(BASH_EXECUTABLE), "缺少精确 Git Bash 路径，跳过 CLI EDD");
        List<EddResult> results = new ArrayList<>();
        results.add(runReadOnly());
        results.add(runMutatingApproval());
        results.add(runDestructiveAdminApproval());
        results.add(runCapabilityDenied());
        results.add(runArgumentInjection());
        results.add(runWorkspaceEscape());
        results.add(runPtyOutput());

        Path report = Path.of("target", "edd", "cli-capability-edd.json");
        Files.createDirectories(report.getParent());
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of("scenarios", results));

        JsonNode reportJson = mapper.readTree(report.toFile());
        assertThat(reportJson.path("scenarios")).hasSize(TASK_IDS.size());
        assertThat(results).extracting(EddResult::taskId).containsExactlyElementsOf(TASK_IDS);
        for (JsonNode scenario : reportJson.path("scenarios")) {
            Set<String> actualFields = new LinkedHashSet<>();
            scenario.fieldNames().forEachRemaining(actualFields::add);
            assertThat(actualFields).containsExactlyInAnyOrderElementsOf(REPORT_FIELDS);
            assertThat(scenario.path("taskId").asText()).isIn(TASK_IDS);
            assertThat(scenario.path("passed").asBoolean()).isTrue();
            JsonNode fingerprint = scenario.get("commandSha256");
            if (fingerprint != null && !fingerprint.isNull()) {
                assertThat(fingerprint.asText()).matches("[0-9a-f]{64}");
            }
            assertThat(scenario.toString()).doesNotContain("payload").doesNotContain("whoami");
        }
        assertThat(results).allSatisfy(result -> assertThat(result.passed()).isTrue());
    }

    private EddResult runReadOnly() {
        AtomicInteger calls = new AtomicInteger();
        TerminalCommandExecutor terminal = fakeTerminal(calls, new CommandResult(0, "read", "", false));
        CliCommandCatalog catalog = catalog(
                new CliCommandDefinition("read", "printf", List.of(), CliRiskLevel.READ_ONLY, Set.of()));
        CliExecutionResultHolder execution = execute(
                catalog, terminal, intent("read", workspace, List.of("read")), new CliAuthorizationContext(Set.of(), false, false));
        return result("cli.read-only", execution, calls.get(), true);
    }

    private EddResult runMutatingApproval() {
        AtomicInteger calls = new AtomicInteger();
        TerminalCommandExecutor terminal = fakeTerminal(calls, new CommandResult(0, "", "", false));
        CliCommandCatalog catalog = catalog(new CliCommandDefinition(
                "write", "printf", List.of(), CliRiskLevel.MUTATING, Set.of(RequiredCapability.TERMINAL)));
        CliExecutionResultHolder execution = execute(
                catalog, terminal, intent("write", workspace, List.of("write")),
                new CliAuthorizationContext(Set.of(RequiredCapability.TERMINAL), false, false));
        boolean passed = execution.authorization().decision() == CliAuthorizationDecision.APPROVAL_REQUIRED
                && calls.get() == 0
                && execution.result() == null;
        return result("cli.mutating-approval", execution, calls.get(), passed);
    }

    private EddResult runDestructiveAdminApproval() {
        AtomicInteger calls = new AtomicInteger();
        TerminalCommandExecutor terminal = fakeTerminal(calls, new CommandResult(0, "destroy", "", false));
        CliCommandCatalog catalog = catalog(new CliCommandDefinition(
                "destroy", "printf", List.of(), CliRiskLevel.DESTRUCTIVE, Set.of(RequiredCapability.TERMINAL)));
        CliExecutionResultHolder execution = execute(
                catalog, terminal, intent("destroy", workspace, List.of("destroy")),
                new CliAuthorizationContext(Set.of(RequiredCapability.TERMINAL), true, true));
        boolean passed = execution.authorization().decision() == CliAuthorizationDecision.ALLOWED
                && calls.get() == 1
                && execution.result() != null
                && execution.result().exitCode() == 0;
        return result("cli.destructive-admin", execution, calls.get(), passed);
    }

    private EddResult runCapabilityDenied() {
        AtomicInteger calls = new AtomicInteger();
        TerminalCommandExecutor terminal = fakeTerminal(calls, new CommandResult(0, "", "", false));
        CliCommandCatalog catalog = catalog(new CliCommandDefinition(
                "write", "printf", List.of(), CliRiskLevel.MUTATING, Set.of(RequiredCapability.TERMINAL)));
        CliExecutionResultHolder execution = execute(
                catalog, terminal, intent("write", workspace, List.of("denied")),
                new CliAuthorizationContext(Set.of(), true, true));
        boolean passed = execution.authorization().decision() == CliAuthorizationDecision.DENIED
                && calls.get() == 0
                && execution.result() == null;
        return result("cli.capability-denied", execution, calls.get(), passed);
    }

    private EddResult runArgumentInjection() {
        AtomicInteger calls = new AtomicInteger();
        TerminalCommandExecutor terminal = fakeTerminal(calls, new CommandResult(0, "", "", false));
        CliCommandCatalog catalog = catalog(
                new CliCommandDefinition("read", "printf", List.of(), CliRiskLevel.READ_ONLY, Set.of()));
        try {
            execute(catalog, terminal, intent("read", workspace, List.of("ok;rm")),
                    new CliAuthorizationContext(Set.of(), false, false));
            return new EddResult("cli.argument-injection", "UNEXPECTED", "EXCEPTION", null, null, null,
                    calls.get(), false);
        } catch (RuntimeException expected) {
            return new EddResult("cli.argument-injection", "REJECTED", "REJECTED", null, null, null,
                    calls.get(), calls.get() == 0);
        }
    }

    private EddResult runWorkspaceEscape() throws IOException {
        Path outside = Files.createTempDirectory(workspace.getParent(), "cli-edd-outside-");
        try {
            AtomicInteger calls = new AtomicInteger();
            TerminalCommandExecutor terminal = fakeTerminal(calls, new CommandResult(0, "", "", false));
            CliCommandCatalog catalog = catalog(
                    new CliCommandDefinition("read", "printf", List.of(), CliRiskLevel.READ_ONLY, Set.of()));
            try {
                execute(catalog, terminal,
                        intent("read", workspace, List.of(), new DockerTarget("image", outside, "/workspace")),
                        new CliAuthorizationContext(Set.of(), false, false));
                return new EddResult("cli.workspace-escape", "UNEXPECTED", "EXCEPTION", null, null, null,
                        calls.get(), false);
            } catch (RuntimeException expected) {
                return new EddResult("cli.workspace-escape", "REJECTED", "REJECTED", null, null, null,
                        calls.get(), calls.get() == 0);
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private EddResult runPtyOutput() {
        AtomicInteger calls = new AtomicInteger();
        List<TerminalLog> logs = new ArrayList<>();
        CliCommandCatalog catalog = catalog(new CliCommandDefinition(
                "print", "printf", List.of("%s\\n"), CliRiskLevel.READ_ONLY, Set.of()));
        try (SandboxTerminalService terminal = new SandboxTerminalService()) {
            TerminalCommandExecutor countingTerminal = (request, consumer) -> {
                calls.incrementAndGet();
                return terminal.execute(request, consumer);
            };
            CliExecutionResultHolder execution = execute(
                    catalog, countingTerminal,
                    intent("print", workspace, List.of("pty output"), new PtyTarget(BASH_EXECUTABLE, workspace)),
                    new CliAuthorizationContext(Set.of(), false, false),
                    logs::add);
            boolean passed = execution.authorization().decision() == CliAuthorizationDecision.ALLOWED
                    && execution.result() != null
                    && execution.result().exitCode() == 0
                    && execution.result().stdout().contains("pty output")
                    && logs.stream().allMatch(log -> log.stream() == Stream.PTY);
            passed = passed && calls.get() == 1;
            return result("cli.pty-output", execution, calls.get(), passed);
        }
    }

    private CliExecutionResultHolder execute(
            CliCommandCatalog catalog,
            TerminalCommandExecutor terminal,
            CliCommandIntent intent,
            CliAuthorizationContext context) {
        return execute(catalog, terminal, intent, context, ignored -> { });
    }

    private CliExecutionResultHolder execute(
            CliCommandCatalog catalog,
            TerminalCommandExecutor terminal,
            CliCommandIntent intent,
            CliAuthorizationContext context,
            java.util.function.Consumer<TerminalLog> logs) {
        GovernedCliCommandExecutor executor = new GovernedCliCommandExecutor(catalog, terminal);
        com.agent.core.cli.CliExecutionResult value = executor.execute(intent, context, logs).join();
        return new CliExecutionResultHolder(
                value.authorization(), value.result().orElse(null));
    }

    private static TerminalCommandExecutor fakeTerminal(AtomicInteger calls, CommandResult result) {
        return (request, logs) -> {
            calls.incrementAndGet();
            logs.accept(new TerminalLog(Stream.STDOUT, result.stdout()));
            return CompletableFuture.completedFuture(result);
        };
    }

    private CliCommandCatalog catalog(CliCommandDefinition definition) {
        return new CliCommandCatalog(List.of(definition));
    }

    private CliCommandIntent intent(String name, Path root, List<String> arguments) {
        return intent(name, root, arguments, new DockerTarget("image", root, "/workspace"));
    }

    private CliCommandIntent intent(String name, Path root, List<String> arguments,
                                    com.agent.sandbox.pty.TerminalTarget target) {
        return new CliCommandIntent(name, arguments, root, target, Duration.ofSeconds(10));
    }

    private EddResult result(String taskId, CliExecutionResultHolder execution, int terminalCalls, boolean passed) {
        return new EddResult(
                taskId,
                execution.result() == null ? "NOT_EXECUTED" : "COMPLETED",
                execution.authorization().decision().name(),
                execution.authorization().plan().commandSha256(),
                execution.result() == null ? null : execution.result().exitCode(),
                execution.result() == null ? null : execution.result().timedOut(),
                terminalCalls,
                passed);
    }

    private record CliExecutionResultHolder(CliAuthorization authorization, CommandResult result) {
    }

    private record EddResult(
            String taskId,
            String status,
            String decision,
            String commandSha256,
            Integer exitCode,
            Boolean timedOut,
            int terminalCalls,
            boolean passed) {
    }
}
