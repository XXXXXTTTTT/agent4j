package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunServiceRewindTest {

    @Test
    void restoresExactHistoricalCheckpointWithoutDeletingHistory() {
        UUID runId = UUID.fromString("c94258b8-0f07-4d34-9b1a-cb3b38bdf4ef");
        RunCheckpoint initial = new RunCheckpoint(
                runId, 0, "flow", RunStatus.RUNNING, AgentState.empty(), "done",
                null, null, null, null, Instant.parse("2026-08-14T00:00:00Z"));
        RunCheckpoint latest = new RunCheckpoint(
                runId, 1, "flow", RunStatus.COMPLETED, AgentState.empty().withTraceEntry("done"), null,
                null, null, null, null, Instant.parse("2026-08-14T00:00:01Z"));
        RewindCheckpointer checkpointer = new RewindCheckpointer(initial, latest);
        GraphRegistry graphRegistry = new GraphRegistry(Map.of(
                "flow", () -> new StateGraph(1)
                        .addNode("done", state -> state)
                        .addEdge("done", StateGraph.END)
                        .setEntryPoint("done")));

        try (AgentRunService service = new AgentRunService(checkpointer, graphRegistry, event -> { })) {
            RunCheckpoint restored = service.rewind(runId, 0);

            assertThat(restored.version()).isZero();
            assertThat(restored.state()).isEqualTo(initial.state());
            assertThat(checkpointer.loadHistory(runId)).containsExactly(initial, latest);
            assertThat(checkpointer.restoredVersion).isZero();
        }
    }

    private static final class RewindCheckpointer implements Checkpointer {
        private final RunCheckpoint initial;
        private final RunCheckpoint latest;
        private long restoredVersion = -1;

        private RewindCheckpointer(RunCheckpoint initial, RunCheckpoint latest) {
            this.initial = initial;
            this.latest = latest;
        }

        @Override
        public RunCheckpoint create(UUID runId, String graphId, AgentState state, String entryNode) {
            return initial;
        }

        @Override
        public RunCheckpoint append(CheckpointAppend append) {
            return latest;
        }

        @Override
        public Optional<RunCheckpoint> loadLatest(UUID runId) {
            return Optional.of(latest);
        }

        @Override
        public List<RunCheckpoint> loadHistory(UUID runId) {
            return List.of(initial, latest);
        }

        @Override
        public List<RunCheckpoint> loadLatestByStatus(RunStatus status) {
            return List.of();
        }

        @Override
        public RunCheckpoint restore(UUID runId, long version) {
            restoredVersion = version;
            return initial;
        }
    }
}
