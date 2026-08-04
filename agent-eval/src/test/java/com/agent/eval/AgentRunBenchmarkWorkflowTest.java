package com.agent.eval;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.CheckpointAppend;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.engine.StateGraph;
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
        GraphFactory factory = () -> new StateGraph(2)
                .addNode("benchmark", state -> state
                        .withVariable("benchmark.passed", "true")
                        .withTraceEntry("benchmark"))
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
                             runId -> events.stream()
                                     .filter(event -> event.runId().equals(runId))
                                     .filter(event -> event instanceof TraceEvent.NodeStarted)
                                     .map(TraceEvent::occurredAt)
                                     .findFirst()))) {
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

    private static final class InMemoryCheckpointer implements Checkpointer {

        private final Map<UUID, List<RunCheckpoint>> histories = new ConcurrentHashMap<>();

        @Override
        public RunCheckpoint create(UUID runId, String graphId, AgentState initialState,
                                    String entryNode) {
            RunCheckpoint checkpoint = new RunCheckpoint(
                    runId, 0, graphId, RunStatus.RUNNING, initialState, entryNode,
                    null, null, null, null, Instant.now());
            if (histories.putIfAbsent(runId, new ArrayList<>(List.of(checkpoint))) != null) {
                throw new IllegalStateException("Run 已存在: " + runId);
            }
            return checkpoint;
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
