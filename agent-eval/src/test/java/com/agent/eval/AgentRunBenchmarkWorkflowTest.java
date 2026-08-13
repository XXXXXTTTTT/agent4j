package com.agent.eval;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.CheckpointAppend;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.engine.Node;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.engine.StateGraph;
import com.agent.core.trace.RunLogEvent;
import com.agent.core.trace.RunLogStream;
import com.agent.core.trace.RunLogPublisher;
import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunBenchmarkWorkflowTest {

    @Test
    void runsVersionedCodeOpsRagAndTraceTasksThroughAgentRunService() {
        InputStream resource = getClass().getResourceAsStream("/benchmark/tasks.jsonl");
        assertThat(resource).isNotNull();
        BenchmarkTaskSet taskSet;
        try (resource) {
            taskSet = new BenchmarkTaskSetReader().read(resource);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        assertThat(taskSet.tasks()).extracting(BenchmarkTask::category)
                .contains("CODE", "OPS", "RAG", "TRACE");

        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        List<TraceEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        ConcurrentHashMap<UUID, Instant> firstLogs = new ConcurrentHashMap<>();
        RunLogPublisher logPublisher = event -> firstLogs.putIfAbsent(event.runId(), event.occurredAt());
        GraphFactory factory = () -> new StateGraph(2)
                .addNode("benchmark", new Node() {
                    @Override
                    public AgentState execute(NodeExecutionContext context, AgentState state) {
                        logPublisher.publish(new RunLogEvent(
                                UUID.randomUUID(), context.runId(), context.nodeName(), 0,
                                RunLogStream.STDOUT, "benchmark output", Instant.now()));
                        return state.withVariable("benchmark.passed", "true")
                                .withTraceEntry("benchmark");
                    }

                    @Override
                    public AgentState execute(AgentState state) {
                        return state;
                    }
                })
                .setEntryPoint("benchmark")
                .addEdge("benchmark", StateGraph.END);
        GraphRegistry registry = new GraphRegistry(Map.of("benchmark-graph", factory));
        try (AgentRunService runService = new AgentRunService(
                checkpointer, registry, events::add);
             BenchmarkRunner runner = new BenchmarkRunner(
                     new AgentRunBenchmarkExecutor(
                             runService,
                             "benchmark-graph",
                             (task, terminal) -> "true".equals(
                                     terminal.state().variables().get("benchmark.passed")),
                             runId -> Optional.ofNullable(firstLogs.get(runId))))) {
            BenchmarkReport report = runner.run(new BenchmarkRunRequest(
                    taskSet, 1, 8, Duration.ofSeconds(5)));

            assertThat(report.taskCount()).isEqualTo(taskSet.tasks().size());
            assertThat(report.passK()).isEqualTo(1.0);
            assertThat(report.results()).allSatisfy(result -> {
                assertThat(result.passed()).isTrue();
                assertThat(result.firstTokenAt()).isPresent();
            });
            assertThat(events).anyMatch(event -> event instanceof TraceEvent.Completed);
            assertThat(report.results()).extracting(BenchmarkTaskResult::taskId)
                    .isSortedAccordingTo(Comparator.naturalOrder());
        }
    }

    @Test
    void allowsEvaluatorToPassWaitingApprovalAsAnObservableOutcome() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        InterruptRequest request = new InterruptRequest(
                UUID.randomUUID(), "approval", "需要审批", Map.of("command", "danger"));
        GraphRegistry registry = new GraphRegistry(Map.of("approval-graph", () ->
                new StateGraph(1, (runId, nodeName, state) -> Optional.of(request))
                        .addNode("approval", state -> state)
                        .addEdge("approval", StateGraph.END)
                        .setEntryPoint("approval")));
        BenchmarkTask task = new BenchmarkTask(
                "hitl.waiting", "OPS", "prompt", "等待审批即通过", Map.of());
        try (AgentRunService runService = new AgentRunService(
                checkpointer, registry, TraceEventPublisher.noop())) {
            AgentRunBenchmarkExecutor executor = new AgentRunBenchmarkExecutor(
                    runService, "approval-graph",
                    (ignored, terminal) -> terminal.status() == RunStatus.WAITING_APPROVAL);

            BenchmarkTaskResult result = executor.execute(task, 1, Duration.ofSeconds(2));

            assertThat(result.passed()).isTrue();
            assertThat(checkpointer.loadLatest(checkpointer.lastRunId()))
                    .get().extracting(RunCheckpoint::status)
                    .isEqualTo(RunStatus.WAITING_APPROVAL);
        }
    }

    @Test
    void preservesFixtureMetadataInInitialStateBeforeStartingAgentRun() {
        InputStream resource = getClass().getResourceAsStream(
                "/benchmarks/mcp-skill-runtime.jsonl");
        assertThat(resource).isNotNull();
        BenchmarkTask task;
        try (resource) {
            task = new BenchmarkTaskSetReader().read(resource).tasks().getFirst();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }

        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        GraphRegistry registry = new GraphRegistry(Map.of("metadata-graph", () ->
                new StateGraph(1)
                        .addNode("complete", state -> state)
                        .addEdge("complete", StateGraph.END)
                        .setEntryPoint("complete")));
        try (AgentRunService runService = new AgentRunService(
                checkpointer, registry, TraceEventPublisher.noop())) {
            AgentRunBenchmarkExecutor executor = new AgentRunBenchmarkExecutor(
                    runService, "metadata-graph", (ignored, terminal) -> true);

            BenchmarkTaskResult result = executor.execute(task, 1, Duration.ofSeconds(2));

            assertThat(result.passed()).isTrue();
            assertThat(checkpointer.loadHistory(checkpointer.lastRunId()).getFirst()
                    .state().variables())
                    .containsEntry("benchmark.metadata.actorUserId", "user-mcp-a")
                    .containsEntry("benchmark.metadata.workspaceId", "ws-mcp-a")
                    .containsEntry("benchmark.metadata.expectedMcpRemoteTool", "echo")
                    .containsEntry("benchmark.metadata.expectedTraceMarker", "mcp.tool.call");
        }
    }

    @Test
    void cancelsUnderlyingRunWhenBenchmarkTimeoutExpires() throws Exception {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        GraphRegistry registry = new GraphRegistry(Map.of("slow-graph", () ->
                new StateGraph(1)
                        .addNode("slow", state -> {
                            entered.countDown();
                            try {
                                Thread.sleep(Duration.ofSeconds(10));
                                return state;
                            } catch (InterruptedException exception) {
                                interrupted.set(true);
                                Thread.currentThread().interrupt();
                                throw exception;
                            }
                        })
                        .addEdge("slow", StateGraph.END)
                        .setEntryPoint("slow")));
        BenchmarkTask task = new BenchmarkTask(
                "ops.timeout", "OPS", "prompt", "timeout fails", Map.of());
        try (AgentRunService runService = new AgentRunService(
                checkpointer, registry, TraceEventPublisher.noop())) {
            AgentRunBenchmarkExecutor executor = new AgentRunBenchmarkExecutor(
                    runService, "slow-graph", (ignored, terminal) -> false);

            FutureTask<BenchmarkTaskResult> execution = new FutureTask<>(
                    () -> executor.execute(task, 1, Duration.ofSeconds(2)));
            Thread.startVirtualThread(execution::run);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            BenchmarkTaskResult result = execution.get(4, TimeUnit.SECONDS);

            assertThat(result.passed()).isFalse();
            assertThat(checkpointer.loadLatest(checkpointer.lastRunId()))
                    .get().extracting(RunCheckpoint::status)
                    .isEqualTo(RunStatus.FAILED);
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!interrupted.get() && System.nanoTime() - deadline < 0) {
                Thread.onSpinWait();
            }
            assertThat(interrupted).isTrue();
        }
    }

    private static final class InMemoryCheckpointer implements Checkpointer {

        private final Map<UUID, List<RunCheckpoint>> histories = new ConcurrentHashMap<>();
        private volatile UUID lastRunId;

        @Override
        public RunCheckpoint create(UUID runId, String graphId, AgentState initialState,
                                    String entryNode) {
            RunCheckpoint checkpoint = new RunCheckpoint(
                    runId, 0, graphId, RunStatus.RUNNING, initialState, entryNode,
                    null, null, null, null, Instant.now());
            if (histories.putIfAbsent(runId, new ArrayList<>(List.of(checkpoint))) != null) {
                throw new IllegalStateException("Run 已存在: " + runId);
            }
            lastRunId = runId;
            return checkpoint;
        }

        UUID lastRunId() {
            return lastRunId;
        }

        @Override
        public synchronized RunCheckpoint append(CheckpointAppend append) {
            List<RunCheckpoint> history = histories.get(append.runId());
            if (history == null) {
                throw new IllegalStateException("Run 不存在: " + append.runId());
            }
            RunCheckpoint latest = history.getLast();
            if (latest.version() != append.expectedVersion()) {
                throw new IllegalStateException("Checkpoint 版本冲突");
            }
            RunCheckpoint next = new RunCheckpoint(
                    append.runId(), append.expectedVersion() + 1, latest.graphId(),
                    append.status(), append.state(), append.nextNode(),
                    append.interruptRequest(), append.approvalDecision(),
                    append.approvalReason(), append.error(), Instant.now());
            history.add(next);
            return next;
        }

        @Override
        public synchronized Optional<RunCheckpoint> loadLatest(UUID runId) {
            List<RunCheckpoint> history = histories.get(runId);
            return history == null ? Optional.empty() : Optional.of(history.getLast());
        }

        @Override
        public synchronized List<RunCheckpoint> loadHistory(UUID runId) {
            List<RunCheckpoint> history = histories.get(runId);
            return history == null ? List.of() : List.copyOf(history);
        }

        @Override
        public synchronized List<RunCheckpoint> loadLatestByStatus(RunStatus status) {
            return histories.values().stream()
                    .map(List::getLast)
                    .filter(checkpoint -> checkpoint.status() == status)
                    .sorted(Comparator.comparing(RunCheckpoint::createdAt))
                    .toList();
        }
    }
}
