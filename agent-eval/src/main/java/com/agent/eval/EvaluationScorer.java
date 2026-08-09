package com.agent.eval;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 将基础 Benchmark 结果与显式遥测聚合为能力报告。 */
public final class EvaluationScorer {

    private EvaluationScorer() {
    }

    public static EvaluationReport score(
            EvaluationSuite suite,
            int repetitions,
            List<BenchmarkTaskResult> results,
            List<EvaluationObservation> observations) {
        Objects.requireNonNull(suite, "suite 不能为空");
        Objects.requireNonNull(results, "results 不能为空");
        Objects.requireNonNull(observations, "observations 不能为空");
        BenchmarkReport benchmark = BenchmarkMetrics.calculate(
                suite.taskSet(), repetitions, results);
        Map<EvaluationKey, EvaluationObservation> observationByKey = indexObservations(
                suite, repetitions, observations);
        Map<EvaluationKey, BenchmarkTaskResult> resultByKey = results.stream()
                .collect(java.util.stream.Collectors.toMap(
                        result -> new EvaluationKey(result.taskId(), result.repetition()),
                        result -> result));
        EnumMap<FailureCategory, Integer> failureCounts = new EnumMap<>(FailureCategory.class);
        for (FailureCategory category : FailureCategory.values()) {
            failureCounts.put(category, 0);
        }
        for (EvaluationObservation observation : observationByKey.values()) {
            failureCounts.merge(observation.failureCategory(), 1, Integer::sum);
        }

        List<EvaluationReport.CapabilityMetrics> metrics = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (EvaluationCapability capability : suite.capabilities()) {
            List<String> taskIds = suite.taskCapabilities().entrySet().stream()
                    .filter(entry -> entry.getValue().equals(capability.id()))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            int passedTasks = 0;
            int tracePassed = 0;
            BigDecimal capabilityCost = BigDecimal.ZERO;
            List<String> failedTaskIds = new ArrayList<>();
            List<Double> ttftMillis = new ArrayList<>();
            for (String taskId : taskIds) {
                boolean taskPassed = true;
                for (int repetition = 1; repetition <= repetitions; repetition++) {
                    EvaluationKey key = new EvaluationKey(taskId, repetition);
                    BenchmarkTaskResult result = resultByKey.get(key);
                    EvaluationObservation observation = observationByKey.get(key);
                    boolean trace = EvaluationTraceScorer.containsInOrder(
                            observation.trace(), capability.requiredTrace());
                    boolean costWithinBudget = observation.costUsd()
                            .compareTo(capability.maxCostUsd()) <= 0;
                    if (trace) {
                        tracePassed++;
                    }
                    taskPassed &= result.passed() && trace && costWithinBudget
                            && observation.failureCategory() == FailureCategory.NONE;
                    capabilityCost = capabilityCost.add(observation.costUsd());
                    inputTokens = Math.addExact(inputTokens, observation.inputTokens());
                    outputTokens = Math.addExact(outputTokens, observation.outputTokens());
                    totalCost = totalCost.add(observation.costUsd());
                    result.ttft().ifPresent(value -> ttftMillis.add(value.toNanos() / 1_000_000.0));
                }
                if (taskPassed) {
                    passedTasks++;
                } else {
                    failedTaskIds.add(taskId);
                }
            }
            metrics.add(new EvaluationReport.CapabilityMetrics(
                    capability.id(), taskIds.size(), passedTasks,
                    (double) passedTasks / taskIds.size(),
                    Math.multiplyExact(taskIds.size(), repetitions), tracePassed,
                    capabilityCost, percentileDuration(ttftMillis, 0.95), failedTaskIds));
        }
        return new EvaluationReport(
                suite.id(), benchmark, metrics, inputTokens, outputTokens,
                totalCost, failureCounts, Instant.now());
    }

    private static Map<EvaluationKey, EvaluationObservation> indexObservations(
            EvaluationSuite suite, int repetitions, List<EvaluationObservation> observations) {
        Set<EvaluationKey> expected = new HashSet<>();
        for (BenchmarkTask task : suite.taskSet().tasks()) {
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                expected.add(new EvaluationKey(task.id(), repetition));
            }
        }
        Map<EvaluationKey, EvaluationObservation> indexed = new HashMap<>();
        for (EvaluationObservation observation : observations) {
            EvaluationKey key = new EvaluationKey(observation.taskId(), observation.repetition());
            if (!expected.contains(key)) {
                throw new IllegalArgumentException("观测包含未知或超出范围的任务键: " + key);
            }
            if (indexed.putIfAbsent(key, observation) != null) {
                throw new IllegalArgumentException("观测任务键不唯一: " + key);
            }
        }
        if (!indexed.keySet().equals(expected)) {
            throw new IllegalArgumentException("观测必须精确覆盖每个任务重复结果");
        }
        return indexed;
    }

    private static Duration percentileDuration(List<Double> values, double quantile) {
        if (values.isEmpty()) {
            return Duration.ZERO;
        }
        values.sort(Comparator.naturalOrder());
        if (values.size() == 1) {
            return Duration.ofNanos(Math.round(values.get(0) * 1_000_000));
        }
        double position = (values.size() - 1) * quantile;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double value = lower == upper ? values.get(lower)
                : values.get(lower) + (values.get(upper) - values.get(lower))
                * (position - lower);
        return Duration.ofNanos(Math.round(value * 1_000_000));
    }

    private record EvaluationKey(String taskId, int repetition) {
    }
}
