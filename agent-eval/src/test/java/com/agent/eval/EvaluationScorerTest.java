package com.agent.eval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationScorerTest {

    private static final Instant BASE = Instant.parse("2026-08-09T00:00:00Z");

    @Test
    void aggregatesCapabilityPassKTraceTokensCostAndFailures() {
        BenchmarkTaskSet taskSet = tasks();
        EvaluationCapability cli = new EvaluationCapability(
                "cli", "7A", List.of("planner", "coder", "ops"),
                0.5, Duration.ofSeconds(2), BigDecimal.ONE);
        EvaluationCapability gui = new EvaluationCapability(
                "gui", "7B", List.of("planner", "gui"),
                0.5, Duration.ofSeconds(2), BigDecimal.ONE);
        EvaluationSuite suite = new EvaluationSuite(
                "chapter-23", taskSet, mapping(taskSet), List.of(gui, cli),
                new EvaluationGatePolicy(0.5, Duration.ofSeconds(5),
                        new BigDecimal("5"), 1));

        List<BenchmarkTaskResult> results = new ArrayList<>();
        List<EvaluationObservation> observations = new ArrayList<>();
        for (BenchmarkTask task : taskSet.tasks()) {
            boolean passed = !task.id().equals("cli-2");
            String failure = passed ? null : "tool protocol rejected";
            results.add(result(task.id(), passed, passed ? null : failure));
            observations.add(new EvaluationObservation(
                    task.id(), 1,
                    task.id().startsWith("cli")
                            ? List.of("planner", "coder", "ops")
                            : List.of("planner", "gui"),
                    10, 5, new BigDecimal("0.10"),
                    passed ? FailureCategory.NONE : FailureCategory.TOOL_PROTOCOL,
                    passed ? "" : failure));
        }

        EvaluationReport report = EvaluationScorer.score(suite, 1, results, observations);

        assertThat(report.suiteId()).isEqualTo("chapter-23");
        assertThat(report.capabilities()).extracting(EvaluationReport.CapabilityMetrics::capabilityId)
                .containsExactly("cli", "gui");
        assertThat(report.capabilities().get(0).passK()).isEqualTo(0.5);
        assertThat(report.capabilities().get(1).passK()).isEqualTo(1.0);
        assertThat(report.totalInputTokens()).isEqualTo(500);
        assertThat(report.totalOutputTokens()).isEqualTo(250);
        assertThat(report.totalCostUsd()).isEqualByComparingTo("5.0000");
        assertThat(report.failureCounts()).containsEntry(FailureCategory.TOOL_PROTOCOL, 1);
    }

    @Test
    void rejectsMissingDuplicateAndUnknownObservationKeys() {
        BenchmarkTaskSet taskSet = tasks();
        EvaluationSuite suite = new EvaluationSuite(
                "suite", taskSet, singleMapping(taskSet, "cli"), List.of(capability("cli")),
                new EvaluationGatePolicy(0.1, Duration.ofSeconds(2), BigDecimal.ONE, 1));
        List<BenchmarkTaskResult> results = taskSet.tasks().stream()
                .map(task -> result(task.id(), true, null)).toList();
        EvaluationObservation observation = new EvaluationObservation(
                "cli-1", 1, List.of("planner"), 1, 1, BigDecimal.ONE,
                FailureCategory.NONE, "");

        assertThatThrownBy(() -> EvaluationScorer.score(
                suite, 1, results, List.of(observation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("观测");

        List<EvaluationObservation> duplicate = new ArrayList<>();
        for (BenchmarkTask task : taskSet.tasks()) {
            duplicate.add(new EvaluationObservation(task.id(), 1, List.of("planner"),
                    1, 1, BigDecimal.ONE, FailureCategory.NONE, ""));
        }
        duplicate.set(1, observation);
        assertThatThrownBy(() -> EvaluationScorer.score(suite, 1, results, duplicate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EvaluationCapability capability(String id) {
        return new EvaluationCapability(id, "7A", List.of("planner"),
                0.1, Duration.ofSeconds(2), BigDecimal.ONE);
    }

    private BenchmarkTaskSet tasks() {
        List<BenchmarkTask> tasks = new ArrayList<>();
        tasks.add(new BenchmarkTask("cli-1", "CLI", "prompt", "criteria", Map.of()));
        tasks.add(new BenchmarkTask("cli-2", "CLI", "prompt", "criteria", Map.of()));
        for (int i = 3; i <= 50; i++) {
            tasks.add(new BenchmarkTask("gui-" + i, "GUI", "prompt", "criteria", Map.of()));
        }
        return new BenchmarkTaskSet(tasks);
    }

    private Map<String, String> mapping(BenchmarkTaskSet tasks) {
        Map<String, String> mapping = new HashMap<>();
        for (BenchmarkTask task : tasks.tasks()) {
            mapping.put(task.id(), task.id().startsWith("cli") ? "cli" : "gui");
        }
        return mapping;
    }

    private Map<String, String> singleMapping(BenchmarkTaskSet tasks, String capabilityId) {
        Map<String, String> mapping = new HashMap<>();
        for (BenchmarkTask task : tasks.tasks()) {
            mapping.put(task.id(), capabilityId);
        }
        return mapping;
    }

    private BenchmarkTaskResult result(String id, boolean passed, String failure) {
        Instant finished = BASE.plusSeconds(1);
        return new BenchmarkTaskResult(id, 1, passed, BASE, Optional.of(BASE.plusMillis(100)),
                finished, failure);
    }
}
