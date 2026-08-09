package com.agent.eval;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class EvaluationTestFixture {

    private static final Instant BASE = Instant.parse("2026-08-09T00:00:00Z");

    private EvaluationTestFixture() {
    }

    static EvaluationReport report(EvaluationGatePolicy policy, boolean includeFailure) {
        List<BenchmarkTask> tasks = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        List<BenchmarkTaskResult> results = new ArrayList<>();
        List<EvaluationObservation> observations = new ArrayList<>();
        for (int index = 1; index <= 50; index++) {
            String id = "task-" + index;
            tasks.add(new BenchmarkTask(id, "EVAL", "prompt", "criteria", Map.of()));
            mapping.put(id, "runtime");
            boolean passed = !includeFailure || index != 1;
            results.add(new BenchmarkTaskResult(
                    id, 1, passed, BASE, Optional.of(BASE.plusMillis(100)),
                    BASE.plusMillis(200), passed ? null : "assertion failed"));
            observations.add(new EvaluationObservation(
                    id, 1, List.of("planner", "end"), 10, 5,
                    new BigDecimal("0.1000"),
                    passed ? FailureCategory.NONE : FailureCategory.ASSERTION,
                    passed ? "" : "assertion failed"));
        }
        BenchmarkTaskSet taskSet = new BenchmarkTaskSet(tasks);
        EvaluationCapability capability = new EvaluationCapability(
                "runtime", "23", List.of("planner", "end"), 0.9,
                Duration.ofSeconds(1), BigDecimal.ONE);
        EvaluationSuite suite = new EvaluationSuite(
                "chapter-23", taskSet, mapping, List.of(capability), policy);
        return EvaluationScorer.score(suite, 1, results, observations);
    }
}
