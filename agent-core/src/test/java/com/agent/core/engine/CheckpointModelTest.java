package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckpointModelTest {

    private static final UUID RUN_ID = UUID.fromString("53ce4e79-0df8-4efb-871b-1d9d3bb96ef0");
    private static final UUID INTERRUPT_ID = UUID.fromString("ce0f234a-87e0-4e52-90bd-c41c3288a6c9");
    private static final String GRAPH_ID = "coder-ops-reviewer";
    private static final String NODE_NAME = "ops";

    @Test
    void validatesApprovalCommand() {
        ApprovalCommand command = new ApprovalCommand(
                ApprovalDecision.APPROVE, 0, "已核对命令和工作区");

        assertThat(command.decision()).isEqualTo(ApprovalDecision.APPROVE);
        assertThat(command.expectedVersion()).isZero();
        assertThat(command.reason()).isEqualTo("已核对命令和工作区");
        assertThatThrownBy(() -> new ApprovalCommand(null, 0, "已核对"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApprovalCommand(ApprovalDecision.APPROVE, -1, "已核对"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalCommand(ApprovalDecision.APPROVE, 0, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freezesInterruptDetailsAndProvidesNeverPolicy() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("command", "rm build.out");
        InterruptRequest request = new InterruptRequest(
                INTERRUPT_ID, NODE_NAME, "需要人工审批", details);
        details.put("command", "changed");

        assertThat(request.details()).containsExactlyEntriesOf(
                Map.of("command", "rm build.out"));
        assertThatThrownBy(() -> request.details().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(InterruptPolicy.never().evaluate(RUN_ID, NODE_NAME, AgentState.empty()))
                .isEmpty();
        assertThatThrownBy(() -> new InterruptRequest(null, NODE_NAME, "原因", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InterruptRequest(INTERRUPT_ID, " ", "原因", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterruptRequest(INTERRUPT_ID, NODE_NAME, " ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterruptRequest(INTERRUPT_ID, NODE_NAME, "原因", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void acceptsEveryValidCheckpointStatus() {
        InterruptRequest interrupt = interruptRequest();

        assertThat(checkpoint(RunStatus.RUNNING, NODE_NAME, null, null, null, null).version())
                .isZero();
        assertThat(checkpoint(
                RunStatus.RUNNING,
                NODE_NAME,
                null,
                ApprovalDecision.APPROVE,
                "已批准",
                null).approvalDecision()).isEqualTo(ApprovalDecision.APPROVE);
        assertThat(checkpoint(
                RunStatus.WAITING_APPROVAL,
                NODE_NAME,
                interrupt,
                null,
                null,
                null).interruptRequest()).isEqualTo(interrupt);
        assertThat(checkpoint(RunStatus.COMPLETED, null, null, null, null, null).status())
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(checkpoint(
                RunStatus.REJECTED,
                null,
                interrupt,
                ApprovalDecision.REJECT,
                "拒绝原因",
                null).status()).isEqualTo(RunStatus.REJECTED);
        assertThat(checkpoint(
                RunStatus.FAILED,
                null,
                null,
                null,
                null,
                "java.io.IOException: failed\n\tat Test.run(Test.java:1)").status())
                .isEqualTo(RunStatus.FAILED);
    }

    @Test
    void rejectsInvalidCheckpointStatusCombinations() {
        assertThatThrownBy(() -> checkpoint(
                RunStatus.RUNNING, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoint(
                RunStatus.RUNNING, NODE_NAME, interruptRequest(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoint(
                RunStatus.WAITING_APPROVAL, NODE_NAME, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoint(
                RunStatus.WAITING_APPROVAL,
                "reviewer",
                interruptRequest(),
                null,
                null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoint(
                RunStatus.COMPLETED, NODE_NAME, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoint(
                RunStatus.REJECTED,
                null,
                interruptRequest(),
                null,
                "拒绝原因",
                null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoint(
                RunStatus.REJECTED,
                null,
                interruptRequest(),
                ApprovalDecision.REJECT,
                " ",
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoint(
                RunStatus.REJECTED,
                null,
                null,
                ApprovalDecision.REJECT,
                "拒绝原因",
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoint(
                RunStatus.FAILED, null, null, null, null, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunCheckpoint(
                RUN_ID,
                -1,
                GRAPH_ID,
                RunStatus.COMPLETED,
                AgentState.empty(),
                null,
                null,
                null,
                null,
                null,
                Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesCheckpointAppendWithTheSameStateRules() {
        CheckpointAppend append = new CheckpointAppend(
                RUN_ID,
                2,
                RunStatus.RUNNING,
                AgentState.empty(),
                NODE_NAME,
                null,
                null,
                null,
                null);

        assertThat(append.expectedVersion()).isEqualTo(2);
        assertThatThrownBy(() -> new CheckpointAppend(
                RUN_ID,
                -1,
                RunStatus.COMPLETED,
                AgentState.empty(),
                null,
                null,
                null,
                null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckpointAppend(
                RUN_ID,
                0,
                RunStatus.WAITING_APPROVAL,
                AgentState.empty(),
                NODE_NAME,
                null,
                null,
                null,
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistenceExceptionsPreserveIdentifiers() {
        CheckpointConflictException conflict = new CheckpointConflictException(RUN_ID, 4);
        RunNotFoundException notFound = new RunNotFoundException(RUN_ID);

        assertThat(conflict.runId()).isEqualTo(RUN_ID);
        assertThat(conflict.expectedVersion()).isEqualTo(4);
        assertThat(notFound.runId()).isEqualTo(RUN_ID);
    }

    private RunCheckpoint checkpoint(
            RunStatus status,
            String nextNode,
            InterruptRequest interruptRequest,
            ApprovalDecision approvalDecision,
            String approvalReason,
            String error) {
        return new RunCheckpoint(
                RUN_ID,
                0,
                GRAPH_ID,
                status,
                AgentState.empty(),
                nextNode,
                interruptRequest,
                approvalDecision,
                approvalReason,
                error,
                Instant.EPOCH);
    }

    private InterruptRequest interruptRequest() {
        return new InterruptRequest(
                INTERRUPT_ID,
                NODE_NAME,
                "需要人工审批",
                Map.of("command", "rm build.out"));
    }
}
