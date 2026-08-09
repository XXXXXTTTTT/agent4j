package com.agent.core.nodes;

import com.agent.core.cli.CliApprovalInterruptPolicy;
import com.agent.core.cli.CliAuthorizationDecision;
import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.cli.CliRiskLevel;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.GraphExecutionRequest;
import com.agent.core.engine.GraphExecutionResult;
import com.agent.core.engine.GraphExecutionListener;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.engine.StateGraph;
import com.agent.core.harness.HarnessEvent;
import com.agent.core.harness.HarnessEventType;
import com.agent.core.harness.HarnessHookChain;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.trace.RunLogEvent;
import com.agent.core.trace.RunLogStream;
import com.agent.sandbox.pty.CommandRequest;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.Stream;
import com.agent.sandbox.pty.TerminalCommandExecutor;
import com.agent.sandbox.pty.TerminalLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpsNodeTest {

    private static final UUID RUN_ID = UUID.fromString(
            "360b7df7-dfed-4b45-bfb7-b607ca86a43e");

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
    void publishesEveryTerminalStreamWithExactContextAndSequence() throws Exception {
        TerminalCommandExecutor executor = (request, logConsumer) -> {
            logConsumer.accept(new TerminalLog(Stream.STDOUT, "out"));
            logConsumer.accept(new TerminalLog(Stream.STDERR, "err"));
            logConsumer.accept(new TerminalLog(Stream.PTY, "\u001b[32mok\u001b[0m"));
            return CompletableFuture.completedFuture(
                    new CommandResult(0, "out", "err", false));
        };
        List<RunLogEvent> events = new CopyOnWriteArrayList<>();
        OpsNode node = new OpsNode(
                executor, target, Duration.ofSeconds(30), events::add);

        AgentState result = node.execute(
                new NodeExecutionContext(RUN_ID, "ops"),
                AgentState.empty().withVariable(OpsNode.COMMAND_KEY, "mvn test"));

        assertThat(events).extracting(RunLogEvent::runId).containsOnly(RUN_ID);
        assertThat(events).extracting(RunLogEvent::nodeName).containsOnly("ops");
        assertThat(events).extracting(RunLogEvent::sequence).containsExactly(0L, 1L, 2L);
        assertThat(events).extracting(RunLogEvent::stream).containsExactly(
                RunLogStream.STDOUT, RunLogStream.STDERR, RunLogStream.PTY);
        assertThat(events).extracting(RunLogEvent::text).containsExactly(
                "out", "err", "\u001b[32mok\u001b[0m");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.eventId()).isNotNull();
            assertThat(event.occurredAt()).isNotNull();
        });
        assertThat(result.variables()).containsEntry(OpsNode.EXIT_CODE_KEY, "0");
    }

    @Test
    void publishesTerminalExecutionThroughHarnessToolBoundary() {
        TerminalCommandExecutor executor = (request, logConsumer) ->
                CompletableFuture.completedFuture(new CommandResult(0, "ok", "", false));
        OpsNode node = new OpsNode(executor, target, Duration.ofSeconds(30));
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        ExecutionBudget budget = new ExecutionBudget(
                Duration.ofSeconds(2), Duration.ofSeconds(1), 100, 2, 2);

        try (StateGraph graph = new StateGraph(
                budget,
                InterruptPolicy.never(),
                new HarnessHookChain(List.of(events::add)))) {
            graph.addNode("ops", node)
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("ops");
            graph.execute(AgentState.empty().withVariable(OpsNode.COMMAND_KEY, "mvn test"));
        }

        assertThat(events).filteredOn(event ->
                        event.eventType() == HarnessEventType.BEFORE_TOOL
                                || event.eventType() == HarnessEventType.AFTER_TOOL)
                .extracting(HarnessEvent::eventType)
                .containsExactly(HarnessEventType.BEFORE_TOOL, HarnessEventType.AFTER_TOOL);
        assertThat(events).filteredOn(event -> event.metadata().containsKey("toolName"))
                .allSatisfy(event -> assertThat(event.metadata())
                        .containsEntry("toolName", "terminal")
                        .containsEntry("command", "mvn test"));
    }

    @Test
    void recordsPublisherFailureWithoutDiscardingCommandResult() throws Exception {
        TerminalCommandExecutor executor = (request, logConsumer) -> {
            logConsumer.accept(new TerminalLog(Stream.PTY, "output"));
            return CompletableFuture.completedFuture(
                    new CommandResult(0, "output", "", false));
        };
        OpsNode node = new OpsNode(
                executor,
                target,
                Duration.ofSeconds(30),
                event -> {
                    throw new IllegalStateException("log unavailable");
                });

        AgentState result = node.execute(
                new NodeExecutionContext(RUN_ID, "ops"),
                AgentState.empty().withVariable(OpsNode.COMMAND_KEY, "mvn test"));

        assertThat(result.variables())
                .containsEntry(OpsNode.EXIT_CODE_KEY, "0")
                .containsEntry(OpsNode.STDOUT_KEY, "output")
                .containsEntry(OpsNode.STDERR_KEY, "")
                .containsEntry(OpsNode.TIMED_OUT_KEY, "false");
        assertThat(result.variables().get(OpsNode.LOG_ERROR_KEY))
                .contains("java.lang.IllegalStateException: log unavailable")
                .contains("at ");
        assertThat(result.variables()).doesNotContainKey(OpsNode.ERROR_KEY);
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
        assertThatThrownBy(() -> new OpsNode(
                executor, target, Duration.ofSeconds(1), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void governedExecutionUsesApprovedPlanAndWritesAuthorizationEvidence() throws Exception {
        AtomicReference<CommandRequest> received = new AtomicReference<>();
        TerminalCommandExecutor executor = (request, logs) -> {
            received.set(request);
            return CompletableFuture.completedFuture(new CommandResult(0, "ok", "", false));
        };
        ObjectMapper mapper = new ObjectMapper();
        CliApprovalInterruptPolicy policy = new CliApprovalInterruptPolicy(
                new CliCommandCatalog(List.of(new CliCommandDefinition(
                        "test.write", "printf", List.of(), CliRiskLevel.MUTATING,
                        Set.of(RequiredCapability.TERMINAL)))),
                target,
                Duration.ofSeconds(30),
                mapper);
        OpsNode node = new OpsNode(executor, policy, event -> { });
        AgentState state = AgentState.empty()
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(OpsNode.COMMAND_NAME_KEY, "test.write")
                .withVariable(OpsNode.COMMAND_ARGUMENTS_KEY, "[\"value.txt\"]")
                .withVariable(PlannerNode.REQUIRED_CAPABILITIES_KEY, "TERMINAL");
        AgentState result;
        try (StateGraph graph = new StateGraph(2, policy)) {
            graph.addNode("ops", node)
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("ops");
            GraphExecutionListener listener = new GraphExecutionListener() {
                @Override
                public void onNodeStarted(String nodeName, AgentState nodeState) {
                }

                @Override
                public void onNodeCompleted(
                        String nodeName, String nextNode, AgentState nodeState) {
                }
            };
            GraphExecutionResult interrupted = graph.execute(new GraphExecutionRequest(
                    RUN_ID, state, "ops", false), listener);
            assertThat(interrupted).isInstanceOf(GraphExecutionResult.Interrupted.class);
            assertThat(received).hasNullValue();
            GraphExecutionResult completed = graph.execute(new GraphExecutionRequest(
                    RUN_ID, state, "ops", true), listener);
            assertThat(completed).isInstanceOf(GraphExecutionResult.Completed.class);
            result = ((GraphExecutionResult.Completed) completed).state();
        }

        assertThat(received.get().bashCommand()).isEqualTo("'printf' 'value.txt'");
        assertThat(result.variables())
                .containsEntry(OpsNode.COMMAND_KEY, "'printf' 'value.txt'")
                .containsEntry(OpsNode.AUTHORIZATION_DECISION_KEY,
                        CliAuthorizationDecision.ALLOWED.name())
                .containsEntry(OpsNode.AUTHORIZATION_REASON_KEY, "用户已批准")
                .containsEntry(OpsNode.EXIT_CODE_KEY, "0");
        assertThat(result.variables().get(OpsNode.COMMAND_SHA256_KEY)).hasSize(64);
    }

    @Test
    void governedExecutionDoesNotCreateTerminalFutureWithoutApproval() {
        AtomicBoolean invoked = new AtomicBoolean();
        TerminalCommandExecutor executor = (request, logs) -> {
            invoked.set(true);
            return CompletableFuture.completedFuture(new CommandResult(0, "", "", false));
        };
        CliApprovalInterruptPolicy policy = new CliApprovalInterruptPolicy(
                new CliCommandCatalog(List.of(new CliCommandDefinition(
                        "test.write", "printf", List.of(), CliRiskLevel.MUTATING,
                        Set.of(RequiredCapability.TERMINAL)))),
                target,
                Duration.ofSeconds(30),
                new ObjectMapper());
        OpsNode node = new OpsNode(executor, policy, event -> { });
        AgentState result = node.execute(AgentState.empty()
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                .withVariable(OpsNode.COMMAND_NAME_KEY, "test.write")
                .withVariable(OpsNode.COMMAND_ARGUMENTS_KEY, "[]")
                .withVariable(PlannerNode.REQUIRED_CAPABILITIES_KEY, "TERMINAL"));

        assertThat(invoked).isFalse();
        assertThat(result.variables())
                .containsEntry(OpsNode.AUTHORIZATION_DECISION_KEY,
                        CliAuthorizationDecision.APPROVAL_REQUIRED.name())
                .containsEntry(OpsNode.AUTHORIZATION_REASON_KEY, "等待用户批准");
        assertThat(result.variables().get(OpsNode.ERROR_KEY))
                .contains("CLI 命令尚未获得执行授权")
                .contains("at ");
    }
}
