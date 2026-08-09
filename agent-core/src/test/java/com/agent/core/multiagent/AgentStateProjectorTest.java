package com.agent.core.multiagent;

import com.agent.core.engine.AgentState;
import com.agent.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentStateProjectorTest {

    private final AgentStateProjector projector = new AgentStateProjector();
    private final AgentDescriptor worker = new AgentDescriptor(
            "worker",
            "worker-graph",
            Set.of("workspacePath", "task.policy"),
            Set.of("worker.result", "worker.evidence"),
            Set.of());

    @Test
    void forkCopiesConversationAndOnlyReadableVariables() {
        AgentState parent = parentState();
        AgentHandoff handoff = handoff(HandoffContextMode.FORK, Set.of("worker.result"));

        AgentState child = projector.project(parent, worker, handoff);

        assertThat(child.messages()).containsExactly(
                ChatMessage.user("原始问题"),
                ChatMessage.assistant("原始回答"),
                ChatMessage.user("执行 worker 子任务"));
        assertThat(child.variables()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "workspacePath", "D:/workspace",
                "task.policy", "safe"));
        assertThat(child.trace()).isEmpty();
    }

    @Test
    void freshStartsWithBriefingOnlyAndRequiresReadableVariables() {
        AgentHandoff handoff = handoff(HandoffContextMode.FRESH, Set.of("worker.result"));

        AgentState child = projector.project(parentState(), worker, handoff);

        assertThat(child.messages()).containsExactly(ChatMessage.user("执行 worker 子任务"));
        assertThat(child.variables()).containsOnlyKeys("workspacePath", "task.policy");

        AgentState missing = new AgentState(List.of(), Map.of("workspacePath", "D:/workspace"), List.of());
        assertThatThrownBy(() -> projector.project(missing, worker, handoff))
                .isInstanceOf(AgentHandoffStateException.class)
                .hasMessageContaining("task.policy");
    }

    @Test
    void mergesRequestedOwnedOutputsWithoutMessagesOrChildTrace() {
        AgentState parent = parentState();
        AgentHandoff handoff = handoff(
                HandoffContextMode.FRESH,
                Set.of("worker.result", "worker.evidence"));
        AgentState initialChild = projector.project(parent, worker, handoff);
        AgentState finalChild = initialChild
                .withMessage(ChatMessage.assistant("子运行回答"))
                .withVariable("worker.result", "done")
                .withVariable("worker.evidence", "tests-pass")
                .withTraceEntry("worker-node");
        UUID childRunId = UUID.randomUUID();

        AgentState merged = projector.merge(
                parent, initialChild, finalChild, worker, handoff, childRunId);

        assertThat(merged.messages()).isEqualTo(parent.messages());
        assertThat(merged.variables()).containsEntry("worker.result", "done")
                .containsEntry("worker.evidence", "tests-pass")
                .containsEntry("private.secret", "hidden");
        assertThat(merged.trace()).containsExactly(
                "parent-node",
                "handoff:" + handoff.taskId() + ":worker:" + childRunId);
    }

    @Test
    void rejectsReadOnlyMutationUnknownOutputAndMissingRequestedOutput() {
        AgentState parent = parentState();
        AgentHandoff handoff = handoff(HandoffContextMode.FRESH, Set.of("worker.result"));
        AgentState initialChild = projector.project(parent, worker, handoff);

        assertThatThrownBy(() -> projector.merge(
                parent,
                initialChild,
                initialChild.withVariable("workspacePath", "D:/other")
                        .withVariable("worker.result", "done"),
                worker,
                handoff,
                UUID.randomUUID()))
                .isInstanceOf(AgentHandoffStateException.class)
                .hasMessageContaining("workspacePath");

        assertThatThrownBy(() -> projector.merge(
                parent,
                initialChild,
                initialChild.withVariable("unknown.result", "bad")
                        .withVariable("worker.result", "done"),
                worker,
                handoff,
                UUID.randomUUID()))
                .isInstanceOf(AgentHandoffStateException.class)
                .hasMessageContaining("unknown.result");

        assertThatThrownBy(() -> projector.merge(
                parent,
                initialChild,
                initialChild,
                worker,
                handoff,
                UUID.randomUUID()))
                .isInstanceOf(AgentHandoffStateException.class)
                .hasMessageContaining("worker.result");
    }

    @Test
    void rejectsConflictingParentOutputWithoutPartialMerge() {
        AgentState parent = parentState().withVariable("worker.result", "old");
        AgentHandoff handoff = handoff(
                HandoffContextMode.FRESH,
                Set.of("worker.result", "worker.evidence"));
        AgentState initialChild = projector.project(parent, worker, handoff);
        AgentState finalChild = initialChild
                .withVariable("worker.result", "new")
                .withVariable("worker.evidence", "tests-pass");

        assertThatThrownBy(() -> projector.merge(
                parent, initialChild, finalChild, worker, handoff, UUID.randomUUID()))
                .isInstanceOf(AgentStateMergeException.class)
                .hasMessageContaining("worker.result");
        assertThat(parent.variables()).doesNotContainKey("worker.evidence");
    }

    private AgentState parentState() {
        return new AgentState(
                List.of(ChatMessage.user("原始问题"), ChatMessage.assistant("原始回答")),
                Map.of(
                        "workspacePath", "D:/workspace",
                        "task.policy", "safe",
                        "private.secret", "hidden"),
                List.of("parent-node"));
    }

    private AgentHandoff handoff(HandoffContextMode mode, Set<String> outputs) {
        return new AgentHandoff(
                UUID.randomUUID(),
                "planner",
                "worker",
                "执行 worker 子任务",
                mode,
                outputs,
                Duration.ofSeconds(5));
    }
}
