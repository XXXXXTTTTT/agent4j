package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.sandbox.pty.CommandRequest;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpsNodeTest {

    @TempDir
    Path workspace;

    private PtyTarget target;

    @BeforeEach
    void createTarget() throws IOException {
        target = new PtyTarget(Files.createFile(workspace.resolve("bash.exe")), workspace);
    }

    @Test
    void writesAllCommandResultFieldsAndTrace() throws Exception {
        AtomicReference<CommandRequest> received = new AtomicReference<>();
        TerminalCommandExecutor executor = (request, logConsumer) -> {
            received.set(request);
            return CompletableFuture.completedFuture(
                    new CommandResult(7, "out", "err", false));
        };
        Duration timeout = Duration.ofSeconds(30);
        OpsNode node = new OpsNode(executor, target, timeout);
        AgentState original = AgentState.empty()
                .withVariable(OpsNode.COMMAND_KEY, "mvn test");

        AgentState result = node.execute(original);

        assertThat(received.get().target()).isSameAs(target);
        assertThat(received.get().bashCommand()).isEqualTo("mvn test");
        assertThat(received.get().timeout()).isEqualTo(timeout);
        assertThat(original.variables()).containsOnlyKeys(OpsNode.COMMAND_KEY);
        assertThat(original.trace()).isEmpty();
        assertThat(result.variables())
                .containsEntry(OpsNode.EXIT_CODE_KEY, "7")
                .containsEntry(OpsNode.STDOUT_KEY, "out")
                .containsEntry(OpsNode.STDERR_KEY, "err")
                .containsEntry(OpsNode.TIMED_OUT_KEY, "false")
                .doesNotContainKey(OpsNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("ops");
    }

    @Test
    void recordsCompleteAsynchronousFailureStack() throws Exception {
        IllegalStateException failure = new IllegalStateException("terminal failed");
        TerminalCommandExecutor executor = (request, logConsumer) ->
                CompletableFuture.failedFuture(failure);
        OpsNode node = new OpsNode(executor, target, Duration.ofSeconds(30));

        AgentState result = node.execute(AgentState.empty()
                .withVariable(OpsNode.COMMAND_KEY, "mvn test"));

        assertThat(result.variables().get(OpsNode.ERROR_KEY))
                .contains("java.util.concurrent.ExecutionException")
                .contains("java.lang.IllegalStateException: terminal failed")
                .contains("at ");
        assertThat(result.variables()).doesNotContainKeys(
                OpsNode.EXIT_CODE_KEY,
                OpsNode.STDOUT_KEY,
                OpsNode.STDERR_KEY,
                OpsNode.TIMED_OUT_KEY);
        assertThat(result.trace()).containsExactly("ops");
    }

    @Test
    void recordsMissingCommandWithoutInvokingExecutor() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        TerminalCommandExecutor executor = (request, logConsumer) -> {
            invoked.set(true);
            return CompletableFuture.completedFuture(
                    new CommandResult(0, "", "", false));
        };
        OpsNode node = new OpsNode(executor, target, Duration.ofSeconds(30));

        AgentState result = node.execute(AgentState.empty());

        assertThat(invoked).isFalse();
        assertThat(result.variables().get(OpsNode.ERROR_KEY))
                .contains("java.lang.IllegalArgumentException")
                .contains(OpsNode.COMMAND_KEY)
                .contains("at ");
        assertThat(result.trace()).containsExactly("ops");
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        TerminalCommandExecutor executor = (request, logConsumer) ->
                CompletableFuture.completedFuture(new CommandResult(0, "", "", false));

        assertThatThrownBy(() -> new OpsNode(null, target, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OpsNode(executor, null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OpsNode(executor, target, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OpsNode(executor, target, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpsNode(executor, target, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
