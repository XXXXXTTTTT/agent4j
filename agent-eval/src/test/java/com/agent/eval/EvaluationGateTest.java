package com.agent.eval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationGateTest {

    @Test
    void reportsGlobalViolationsInStableMetricOrder() {
        EvaluationReport report = EvaluationTestFixture.report(
                new EvaluationGatePolicy(1.0, Duration.ofMillis(50),
                        BigDecimal.ONE, 0), true);

        EvaluationGateResult result = EvaluationGate.evaluate(report);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations())
                .extracting(EvaluationGateResult.Violation::metric)
                .startsWith("passK", "ttftP95", "costUsd", "failureCount");
    }

    @Test
    void passesWithinThresholdsAndThrowsRedactedTypedFailure() {
        EvaluationReport passing = EvaluationTestFixture.report(
                new EvaluationGatePolicy(0.9, Duration.ofSeconds(1),
                        new BigDecimal("10"), 1), true);
        assertThat(EvaluationGate.evaluate(passing).passed()).isTrue();

        EvaluationReport failing = EvaluationTestFixture.report(
                new EvaluationGatePolicy(1.0, Duration.ofMillis(50),
                        BigDecimal.ONE, 0), true);
        assertThatThrownBy(() -> EvaluationGate.assertPassed(failing))
                .isInstanceOf(EvaluationGateViolationException.class)
                .hasMessageContaining("passK")
                .hasMessageNotContaining("prompt")
                .hasMessageNotContaining("Bearer ")
                .hasMessageNotContaining("sk-");
    }
}
