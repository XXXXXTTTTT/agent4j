package com.agent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationTraceScorerTest {

    @Test
    void scoresRequiredTraceAsOrderedCaseSensitiveSubsequence() {
        assertThat(EvaluationTraceScorer.containsInOrder(
                List.of("planner", "tool", "reviewer"),
                List.of("planner", "reviewer"))).isTrue();
        assertThat(EvaluationTraceScorer.containsInOrder(
                List.of("planner", "tool"), List.of("Planner"))).isFalse();
    }

    @Test
    void rejectsNullEventsAndHandlesEmptyRequiredTrace() {
        assertThat(EvaluationTraceScorer.containsInOrder(List.of("planner"), List.of())).isTrue();
        assertThat(EvaluationTraceScorer.containsInOrder(List.of("planner"), List.of("planner"))).isTrue();
        assertThat(EvaluationTraceScorer.containsInOrder(List.of("planner"), List.of("tool"))).isFalse();
    }
}
