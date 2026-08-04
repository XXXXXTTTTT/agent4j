package com.agent.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 计算 Benchmark 的 pass^k 和 TTFT 指标。 */
public final class BenchmarkMetrics {

    private BenchmarkMetrics() {
    }

    /** 校验完整重复结果并生成按任务 ID 稳定排序的报告。 */
    public static BenchmarkReport calculate(BenchmarkTaskSet taskSet, int repetitions,
                                             List<BenchmarkTaskResult> inputResults) {
        Objects.requireNonNull(taskSet, "taskSet 不能为空");
        Objects.requireNonNull(inputResults, "results 不能为空");
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions 必须大于 0");
        }
        Set<String> taskIds = taskSet.tasks().stream()
                .map(BenchmarkTask::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, Set<Integer>> repetitionsByTask = new HashMap<>();
        List<BenchmarkTaskResult> results = new ArrayList<>(inputResults.size());
        for (BenchmarkTaskResult result : inputResults) {
            Objects.requireNonNull(result, "results 不能包含 null");
            if (!taskIds.contains(result.taskId())) {
                throw new IllegalArgumentException("结果包含未知任务: " + result.taskId());
            }
            if (result.repetition() > repetitions) {
                throw new IllegalArgumentException("重复序号超出配置: " + result.taskId());
            }
            Set<Integer> seen = repetitionsByTask.computeIfAbsent(
                    result.taskId(), ignored -> new HashSet<>());
            if (!seen.add(result.repetition())) {
                throw new IllegalArgumentException("任务重复结果不唯一: " + result.taskId());
            }
            results.add(result);
        }
        for (String taskId : taskIds) {
            Set<Integer> seen = repetitionsByTask.get(taskId);
            if (seen == null || seen.size() != repetitions
                    || !seen.containsAll(java.util.stream.IntStream.rangeClosed(1, repetitions)
                    .boxed().toList())) {
                throw new IllegalArgumentException("任务缺少重复结果: " + taskId);
            }
        }
        results.sort(Comparator.comparing(BenchmarkTaskResult::taskId)
                .thenComparingInt(BenchmarkTaskResult::repetition));

        int passedTaskCount = 0;
        int failedExecutionCount = 0;
        Map<String, List<BenchmarkTaskResult>> byTask = new HashMap<>();
        for (BenchmarkTaskResult result : results) {
            byTask.computeIfAbsent(result.taskId(), ignored -> new ArrayList<>()).add(result);
            if (!result.passed()) {
                failedExecutionCount++;
            }
        }
        for (String taskId : taskIds) {
            if (byTask.get(taskId).stream().allMatch(BenchmarkTaskResult::passed)) {
                passedTaskCount++;
            }
        }
        return new BenchmarkReport(
                results,
                repetitions,
                taskSet.tasks().size(),
                passedTaskCount,
                (double) passedTaskCount / taskSet.tasks().size(),
                failedExecutionCount,
                calculateTtft(results),
                Instant.now());
    }

    /** 与 calculate 同义，便于调用方按聚合语义命名。 */
    public static BenchmarkReport aggregate(BenchmarkTaskSet taskSet, int repetitions,
                                             List<BenchmarkTaskResult> results) {
        return calculate(taskSet, repetitions, results);
    }

    private static BenchmarkReport.TtftMetrics calculateTtft(List<BenchmarkTaskResult> results) {
        List<Double> values = new ArrayList<>();
        for (BenchmarkTaskResult result : results) {
            result.ttft().ifPresent(duration -> values.add(toMillis(duration)));
        }
        if (values.isEmpty()) {
            return new BenchmarkReport.TtftMetrics(0, 0, 0, 0, 0);
        }
        values.sort(Double::compareTo);
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        return new BenchmarkReport.TtftMetrics(
                values.size(),
                sum / values.size(),
                percentile(values, 0.50),
                percentile(values, 0.95),
                values.get(values.size() - 1));
    }

    private static double toMillis(Duration duration) {
        try {
            return duration.toNanos() / 1_000_000.0;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("TTFT 时间跨度超出可计算范围", exception);
        }
    }

    private static double percentile(List<Double> sorted, double quantile) {
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double position = (sorted.size() - 1) * quantile;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = position - lower;
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * fraction;
    }
}
