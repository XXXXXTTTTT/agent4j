package com.agent.rag.memory;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunBadCaseAttributorTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");

    @Test
    void attributesFailedRunFromAllowlistedEvidence() {
        AtomicReference<MemoryCapture> captured = new AtomicReference<>();
        MemoryManager manager = manager(captured);
        AgentState state = new AgentState(List.of(), Map.of(
                "planner.repositoryId", "repo-1",
                "planner.userId", "user-1",
                "planner.task", "fix build",
                "reviewer.approved", "false",
                "reviewer.summary", "failed",
                "reviewer.feedback", "retry",
                "ops.exitCode", "2"), List.of());
        RunBadCaseAttributor attributor = new RunBadCaseAttributor(
                checkpointer(state, RunStatus.FAILED), manager);

        attributor.publish(new TraceEvent.Failed(UUID.randomUUID(), RUN_ID, 3, NOW, "boom"));

        assertThat(captured).hasValueSatisfying(capture -> {
            assertThat(capture.repositoryId()).isEqualTo("repo-1");
            assertThat(capture.userId()).isEqualTo("user-1");
            assertThat(capture.sourceText()).contains("reviewer.approved=false");
            assertThat(capture.sourceText()).startsWith("仅返回 BAD_CASE");
        });
    }

    @Test
    void ignoresSuccessfulCompletedRun() {
        AtomicReference<MemoryCapture> captured = new AtomicReference<>();
        AgentState state = new AgentState(List.of(), Map.of(
                "planner.repositoryId", "repo-1", "planner.userId", "user-1",
                "planner.task", "task", "ops.exitCode", "0",
                "ops.timedOut", "false", "reviewer.approved", "true"), List.of());
        RunBadCaseAttributor attributor = new RunBadCaseAttributor(
                checkpointer(state, RunStatus.COMPLETED), manager(captured));

        attributor.publish(new TraceEvent.Completed(UUID.randomUUID(), RUN_ID, 3, NOW));

        assertThat(captured.get()).isNull();
    }

    @Test
    void rejectsInvalidExitCodeAndMissingScope() {
        AgentState invalidExit = new AgentState(List.of(), Map.of(
                "planner.repositoryId", "repo", "planner.userId", "user",
                "planner.task", "task", "ops.exitCode", "2x"), List.of());
        RunBadCaseAttributor attributor = new RunBadCaseAttributor(
                checkpointer(invalidExit, RunStatus.FAILED), manager(new AtomicReference<>()));
        assertThatThrownBy(() -> attributor.publish(
                new TraceEvent.Failed(UUID.randomUUID(), RUN_ID, 3, NOW, "boom")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ops.exitCode");

        AgentState missingScope = new AgentState(List.of(), Map.of(
                "planner.userId", "user", "planner.task", "task"), List.of());
        RunBadCaseAttributor missing = new RunBadCaseAttributor(
                checkpointer(missingScope, RunStatus.FAILED), manager(new AtomicReference<>()));
        assertThatThrownBy(() -> missing.publish(
                new TraceEvent.Failed(UUID.randomUUID(), RUN_ID, 3, NOW, "boom")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planner.repositoryId");
    }

    private MemoryManager manager(AtomicReference<MemoryCapture> captured) {
        return new MemoryManager(
                capture -> {
                    captured.set(capture);
                    return List.of(new MemoryDraft(MemoryType.BAD_CASE, "bad", capture.sourceText()));
                },
                new MemoryStore() {
                    public List<MemoryEntry> upsertAll(List<MemoryEntry> entries) { return entries; }
                    public List<MemoryRetrievalRow> findByVector(MemoryQuery query, float[] embedding, int limit) { return List.of(); }
                    public List<MemoryRetrievalRow> findByLexical(MemoryQuery query, int limit) { return List.of(); }
                },
                new com.agent.rag.embedding.EmbeddingModel() {
                    public int dimensions() { return 8; }
                    public float[] embed(String text) { return new float[8]; }
                },
                java.time.Clock.systemUTC(), UUID::randomUUID);
    }

    private Checkpointer checkpointer(AgentState state, RunStatus status) {
        RunCheckpoint checkpoint = new RunCheckpoint(
                RUN_ID, 3, "graph", status, state, null, null, null, null,
                status == RunStatus.FAILED ? "boom" : null, NOW);
        return new Checkpointer() {
            public RunCheckpoint create(UUID id, String graph, AgentState initial, String entry) { return checkpoint; }
            public RunCheckpoint append(com.agent.core.engine.CheckpointAppend append) { return checkpoint; }
            public Optional<RunCheckpoint> loadLatest(UUID id) { return Optional.of(checkpoint); }
            public List<RunCheckpoint> loadHistory(UUID id) { return List.of(checkpoint); }
            public List<RunCheckpoint> loadLatestByStatus(RunStatus value) { return List.of(); }
        };
    }
}
