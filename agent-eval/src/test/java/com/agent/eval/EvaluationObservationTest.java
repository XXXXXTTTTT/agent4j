package com.agent.eval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationObservationTest {

    @Test
    void acceptsSuccessfulTelemetryAndNormalizesCost() {
        EvaluationObservation observation = new EvaluationObservation(
                "task", 1, List.of("planner", "tool"), 12, 8,
                new BigDecimal("0.12345"), FailureCategory.NONE, "");

        assertThat(observation.costUsd()).isEqualByComparingTo("0.1235");
        assertThat(observation.failureDetail()).isEmpty();
        assertThat(observation.trace()).containsExactly("planner", "tool");
    }

    @Test
    void requiresFailureCategoryAndRedactedFailureDetail() {
        assertThatThrownBy(() -> new EvaluationObservation(
                "task", 1, List.of("planner"), 1, 1, BigDecimal.ONE,
                FailureCategory.TIMEOUT, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationObservation(
                "task", 1, List.of("planner"), 1, 1, BigDecimal.ONE,
                FailureCategory.NONE, "failure"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationObservation(
                "task", 1, List.of("planner"), -1, 1, BigDecimal.ONE,
                FailureCategory.NONE, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationObservation(
                "task", 1, List.of("planner"), 1, 1, BigDecimal.ONE,
                FailureCategory.TIMEOUT, "Bearer secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationObservation(
                "task", 1, List.of("planner"), 1, 1, BigDecimal.ONE,
                FailureCategory.TIMEOUT, "sk-secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
