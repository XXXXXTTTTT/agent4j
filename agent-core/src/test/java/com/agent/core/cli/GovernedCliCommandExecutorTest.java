package com.agent.core.cli;

import com.agent.core.intent.RequiredCapability;
import com.agent.sandbox.pty.CommandRequest;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import com.agent.sandbox.pty.TerminalLog;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedCliCommandExecutorTest {

    @TempDir
    Path workspace;

    @Test
    void deniedAndWaitingDecisionsNeverCallTerminal() {
        AtomicInteger calls = new AtomicInteger();
        TerminalCommandExecutor terminal = (request, logs) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new CommandResult(0, "ok", "", false));
        };
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                new CliCommandDefinition(
                        "write", "printf", List.of(), CliRiskLevel.MUTATING,
                        Set.of(RequiredCapability.TERMINAL))));
        GovernedCliCommandExecutor executor = new GovernedCliCommandExecutor(catalog, terminal);

        CliExecutionResult result = executor.execute(
                intent("write"),
                new CliAuthorizationContext(Set.of(RequiredCapability.TERMINAL), false, false),
                ignored -> { }).join();

        assertThat(result.authorization().decision()).isEqualTo(CliAuthorizationDecision.APPROVAL_REQUIRED);
        assertThat(result.result()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void deniedCapabilityNeverCallsTerminal() {
        AtomicInteger calls = new AtomicInteger();
        TerminalCommandExecutor terminal = (request, logs) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new CommandResult(0, "ok", "", false));
        };
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                new CliCommandDefinition(
                        "write", "printf", List.of(), CliRiskLevel.MUTATING,
                        Set.of(RequiredCapability.TERMINAL))));
        GovernedCliCommandExecutor executor = new GovernedCliCommandExecutor(catalog, terminal);

        CliExecutionResult result = executor.execute(
                intent("write"),
                new CliAuthorizationContext(Set.of(), true, true),
                ignored -> { }).join();

        assertThat(result.authorization().decision()).isEqualTo(CliAuthorizationDecision.DENIED);
        assertThat(result.result()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void allowedDecisionCallsTerminalOnceAndPreservesResultAndLogs() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CommandRequest> request = new AtomicReference<>();
        AtomicReference<java.util.function.Consumer<TerminalLog>> consumer = new AtomicReference<>();
        TerminalCommandExecutor terminal = (actualRequest, actualConsumer) -> {
            calls.incrementAndGet();
            request.set(actualRequest);
            consumer.set(actualConsumer);
            actualConsumer.accept(new TerminalLog(com.agent.sandbox.pty.Stream.PTY, "ok"));
            return CompletableFuture.completedFuture(new CommandResult(3, "ok", "", false));
        };
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                new CliCommandDefinition("read", "printf", List.of(), CliRiskLevel.READ_ONLY, Set.of())));
        GovernedCliCommandExecutor executor = new GovernedCliCommandExecutor(catalog, terminal);
        AtomicReference<TerminalLog> observedLog = new AtomicReference<>();

        CliExecutionResult result = executor.execute(
                intent("read"),
                new CliAuthorizationContext(Set.of(), false, false),
                observedLog::set).join();

        assertThat(result.authorization().decision()).isEqualTo(CliAuthorizationDecision.ALLOWED);
        assertThat(result.result()).contains(new CommandResult(3, "ok", "", false));
        assertThat(calls).hasValue(1);
        assertThat(request.get().bashCommand()).contains("'printf'");
        assertThat(observedLog).hasValue(new TerminalLog(com.agent.sandbox.pty.Stream.PTY, "ok"));
        assertThat(consumer).isNotNull();
    }

    @Test
    void terminalFailureRemainsExceptional() {
        IllegalStateException failure = new IllegalStateException("terminal failed");
        TerminalCommandExecutor terminal = (request, logs) -> CompletableFuture.failedFuture(failure);
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                new CliCommandDefinition("read", "printf", List.of(), CliRiskLevel.READ_ONLY, Set.of())));
        GovernedCliCommandExecutor executor = new GovernedCliCommandExecutor(catalog, terminal);

        assertThatThrownBy(() -> executor.execute(
                intent("read"),
                new CliAuthorizationContext(Set.of(), false, false),
                ignored -> { }).join())
                .hasCause(failure);
    }

    private CliCommandIntent intent(String name) {
        return new CliCommandIntent(
                name,
                List.of("payload"),
                workspace,
                new DockerTarget("image", workspace, "/workspace"),
                Duration.ofSeconds(10));
    }
}
