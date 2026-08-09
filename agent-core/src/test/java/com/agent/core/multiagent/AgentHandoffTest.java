package com.agent.core.multiagent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentHandoffTest {

    @Test
    void freezesOutputKeysAndAcceptsTenMinuteTimeout() {
        AgentHandoff handoff = new AgentHandoff(
                UUID.randomUUID(),
                "planner",
                "worker",
                "执行精确子任务",
                HandoffContextMode.FORK,
                Set.of("worker.result"),
                Duration.ofMinutes(10));

        assertThat(handoff.requestedOutputKeys()).containsExactly("worker.result");
        assertThatThrownBy(() -> handoff.requestedOutputKeys().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEmptyOutputsAndOutOfRangeTimeout() {
        assertThatThrownBy(() -> handoff(Set.of(), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handoff(Set.of("worker.result"), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handoff(
                Set.of("worker.result"), Duration.ofMinutes(10).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void descendsWithinBoundsAndRejectsCyclesAndExhaustion() {
        HandoffExecutionContext root = HandoffExecutionContext.root("planner", 2, 2);
        HandoffExecutionContext child = root.descend("worker");

        assertThat(child.currentDepth()).isEqualTo(1);
        assertThat(child.remainingHandoffs()).isEqualTo(1);
        assertThat(child.visitedAgents()).containsExactly("planner", "worker");

        assertThatThrownBy(() -> child.descend("planner"))
                .isInstanceOf(AgentHandoffDeniedException.class);
        assertThatThrownBy(() -> HandoffExecutionContext.root("planner", 1, 2)
                .descend("worker")
                .descend("reviewer"))
                .isInstanceOf(AgentHandoffDeniedException.class);
        assertThatThrownBy(() -> HandoffExecutionContext.root("planner", 3, 1)
                .descend("worker")
                .descend("reviewer"))
                .isInstanceOf(AgentHandoffDeniedException.class);
    }

    @Test
    void rejectsContextWhoseVisitedTailDoesNotMatchDepth() {
        assertThatThrownBy(() -> new HandoffExecutionContext(
                1, 3, 2, List.of("planner")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AgentHandoff handoff(Set<String> outputs, Duration timeout) {
        return new AgentHandoff(
                UUID.randomUUID(),
                "planner",
                "worker",
                "执行精确子任务",
                HandoffContextMode.FRESH,
                outputs,
                timeout);
    }
}
