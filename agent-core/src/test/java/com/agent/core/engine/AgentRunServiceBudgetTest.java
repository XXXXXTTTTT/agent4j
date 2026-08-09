package com.agent.core.engine;

import com.agent.core.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunServiceBudgetTest {

    @Test
    void persistsBudgetFailureWithLastCompletedStateAndTrace() throws InterruptedException {
        AwaitingCheckpointer checkpointer = new AwaitingCheckpointer();
        List<TraceEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch failedPublished = new CountDownLatch(1);
        ExecutionBudget budget = new ExecutionBudget(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 5, 5, 5);
        GraphRegistry registry = new GraphRegistry(Map.of("budget", () ->
                new StateGraph(budget, InterruptPolicy.never())
                        .addNode("prepare", state -> state.withVariable("kept", "yes"))
                        .addNode("model", state -> {
                            NodeExecutionContext.consumeTokens(6);
                            return state;
                        })
                        .addEdge("prepare", "model")
                        .addEdge("model", StateGraph.END)
                        .setEntryPoint("prepare")));

        try (AgentRunService service = new AgentRunService(checkpointer, registry, event -> {
            events.add(event);
            if (event instanceof TraceEvent.Failed) {
                failedPublished.countDown();
            }
        })) {
            RunCheckpoint started = service.start("budget", AgentState.empty());
            RunCheckpoint failed = checkpointer.awaitStatus(
                    started.runId(), RunStatus.FAILED, Duration.ofSeconds(5));

            assertThat(failed.state().variables())
                    .containsEntry("kept", "yes")
                    .containsEntry("runtime.stopReason", "TOKEN_BUDGET")
                    .containsEntry("runtime.observed", "6")
                    .containsEntry("runtime.limit", "5")
                    .containsEntry("runtime.consumedTokens", "6");
            assertThat(failed.error())
                    .contains(ExecutionBudgetExceededException.class.getName())
                    .contains("TOKEN_BUDGET");
            assertThat(failedPublished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).anyMatch(event -> event instanceof TraceEvent.Failed);
        }
    }

    private static final class AwaitingCheckpointer implements Checkpointer {

        private final Map<UUID, List<RunCheckpoint>> histories = new LinkedHashMap<>();

        @Override
        public synchronized RunCheckpoint create(
                UUID runId,
                String graphId,
                AgentState initialState,
                String entryNode) {
            RunCheckpoint checkpoint = new RunCheckpoint(
                    runId,
                    0,
                    graphId,
                    RunStatus.RUNNING,
                    initialState,
                    entryNode,
                    null,
                    null,
                    null,
                    null,
                    Instant.now());
            histories.put(runId, new ArrayList<>(List.of(checkpoint)));
            notifyAll();
            return checkpoint;
        }

        @Override
        public synchronized RunCheckpoint append(CheckpointAppend append) {
            List<RunCheckpoint> history = histories.get(append.runId());
            RunCheckpoint latest = history.getLast();
            if (latest.version() != append.expectedVersion()) {
                throw new CheckpointConflictException(append.runId(), append.expectedVersion());
            }
            RunCheckpoint checkpoint = new RunCheckpoint(
                    append.runId(),
                    latest.version() + 1,
                    latest.graphId(),
                    append.status(),
                    append.state(),
                    append.nextNode(),
                    append.interruptRequest(),
                    append.approvalDecision(),
                    append.approvalReason(),
                    append.error(),
                    Instant.now());
            history.add(checkpoint);
            notifyAll();
            return checkpoint;
        }

        @Override
        public synchronized Optional<RunCheckpoint> loadLatest(UUID runId) {
            List<RunCheckpoint> history = histories.get(runId);
            return history == null ? Optional.empty() : Optional.of(history.getLast());
        }

        @Override
        public synchronized List<RunCheckpoint> loadHistory(UUID runId) {
            return List.copyOf(histories.getOrDefault(runId, List.of()));
        }

        @Override
        public synchronized List<RunCheckpoint> loadLatestByStatus(RunStatus status) {
            return histories.values().stream()
                    .map(List::getLast)
                    .filter(checkpoint -> checkpoint.status() == status)
                    .toList();
        }

        private synchronized RunCheckpoint awaitStatus(
                UUID runId,
                RunStatus status,
                Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                RunCheckpoint checkpoint = loadLatest(runId).orElseThrow();
                if (checkpoint.status() == status) {
                    return checkpoint;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("等待 Run 状态超时: " + status);
                }
                try {
                    long millis = Math.max(1, Duration.ofNanos(remaining).toMillis());
                    wait(millis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待 Run 状态被中断", exception);
                }
            }
        }
    }
}
