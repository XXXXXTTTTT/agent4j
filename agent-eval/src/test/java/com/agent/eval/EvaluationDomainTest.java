package com.agent.eval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationDomainTest {

    @Test
    void normalizesCapabilityCostAndDefensivelyCopiesTrace() {
        List<String> trace = new ArrayList<>(List.of("planner", "tool"));
        EvaluationCapability capability = new EvaluationCapability(
                "cli-repair", "7A", trace, 0.8,
                Duration.ofSeconds(2), new BigDecimal("1.23456"));

        trace.add("reviewer");

        assertThat(capability.requiredTrace()).containsExactly("planner", "tool");
        assertThat(capability.maxCostUsd()).isEqualByComparingTo("1.2346");
        assertThatThrownBy(() -> capability.requiredTrace().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesCapabilityThresholdsAndExactEnumValues() {
        assertThat(FailureCategory.values()).containsExactly(
                FailureCategory.NONE,
                FailureCategory.ROUTING,
                FailureCategory.MODEL_TRANSPORT,
                FailureCategory.TOOL_PROTOCOL,
                FailureCategory.AUTHORIZATION,
                FailureCategory.TIMEOUT,
                FailureCategory.BUDGET,
                FailureCategory.PERSISTENCE,
                FailureCategory.ASSERTION,
                FailureCategory.UNKNOWN);

        assertThatThrownBy(() -> new EvaluationCapability(
                "", "7A", List.of("planner"), 0.8,
                Duration.ofSeconds(1), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationCapability(
                "id", "7A", List.of("planner"), 1.1,
                Duration.ofSeconds(1), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationCapability(
                "id", "7A", List.of("planner"), 0.8,
                Duration.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationCapability(
                "id", "7A", List.of("planner"), 0.8,
                Duration.ofSeconds(1), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesGatePolicy() {
        EvaluationGatePolicy policy = new EvaluationGatePolicy(
                0.75, Duration.ofSeconds(3), new BigDecimal("2.00001"), 4);

        assertThat(policy.minPassK()).isEqualTo(0.75);
        assertThat(policy.maxTtftP95()).isEqualTo(Duration.ofSeconds(3));
        assertThat(policy.maxCostUsd()).isEqualByComparingTo("2.0000");
        assertThat(policy.maxFailureCount()).isEqualTo(4);
        assertThatThrownBy(() -> new EvaluationGatePolicy(
                -0.1, Duration.ofSeconds(1), BigDecimal.ONE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationGatePolicy(
                0.1, Duration.ofSeconds(1), BigDecimal.ONE, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCapabilitiesWithoutMappedTasks() {
        List<BenchmarkTask> tasks = new java.util.ArrayList<>();
        for (int index = 1; index <= 50; index++) {
            tasks.add(new BenchmarkTask("task-" + index, "CLI", "prompt", "criteria", java.util.Map.of()));
        }
        BenchmarkTaskSet taskSet = new BenchmarkTaskSet(tasks);
        java.util.Map<String, String> mapping = new java.util.HashMap<>();
        for (BenchmarkTask task : tasks) {
            mapping.put(task.id(), "cli");
        }

        assertThatThrownBy(() -> new EvaluationSuite(
                "suite",
                taskSet,
                mapping,
                List.of(capability("cli"), capability("unused")),
                new EvaluationGatePolicy(0.5, Duration.ofSeconds(1), BigDecimal.ONE, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("能力必须至少绑定一个任务");
    }

    private EvaluationCapability capability(String id) {
        return new EvaluationCapability(
                id, "7A", List.of("planner"), 0.5,
                Duration.ofSeconds(1), BigDecimal.ONE);
    }
}
