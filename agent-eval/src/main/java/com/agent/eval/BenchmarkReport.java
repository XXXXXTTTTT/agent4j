package com.agent.eval;

import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Benchmark 的不可变聚合报告。 */
public record BenchmarkReport(
        List<BenchmarkTaskResult> results,
        List<TaskMetrics> taskMetrics,
        int repetitions,
        int taskCount,
        int passedTaskCount,
        double passK,
        int failedExecutionCount,
        TtftMetrics ttft,
        Instant generatedAt) {

    public BenchmarkReport {
        results = List.copyOf(Objects.requireNonNull(results, "results 不能为空"));
        taskMetrics = List.copyOf(Objects.requireNonNull(
                taskMetrics, "taskMetrics 不能为空"));
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions 必须大于 0");
        }
        if (taskCount < 1) {
            throw new IllegalArgumentException("taskCount 必须大于 0");
        }
        if (passedTaskCount < 0 || passedTaskCount > taskCount) {
            throw new IllegalArgumentException("passedTaskCount 超出范围");
        }
        if (!Double.isFinite(passK) || passK < 0 || passK > 1) {
            throw new IllegalArgumentException("passK 必须位于 0 到 1 之间");
        }
        if (failedExecutionCount < 0) {
            throw new IllegalArgumentException("failedExecutionCount 不能为负数");
        }
        ttft = Objects.requireNonNull(ttft, "ttft 不能为空");
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt 不能为空");
        validateResults(results, taskMetrics, repetitions, taskCount, passedTaskCount,
                passK, failedExecutionCount, ttft.count());
    }

    private static void validateResults(
            List<BenchmarkTaskResult> results,
            List<TaskMetrics> taskMetrics,
            int repetitions,
            int taskCount,
            int passedTaskCount,
            double passK,
            int failedExecutionCount,
            int ttftCount) {
        final int expectedResults;
        try {
            expectedResults = Math.multiplyExact(repetitions, taskCount);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("结果数量超出范围", exception);
        }
        if (results.size() != expectedResults) {
            throw new IllegalArgumentException("结果数量必须等于 taskCount * repetitions");
        }
        if (taskMetrics.size() != taskCount) {
            throw new IllegalArgumentException("逐任务指标数量必须等于 taskCount");
        }
        Map<String, Set<Integer>> seen = new HashMap<>();
        Map<String, Boolean> taskPassed = new HashMap<>();
        int actualFailedExecutions = 0;
        int actualTtftCount = 0;
        for (BenchmarkTaskResult result : results) {
            if (result.repetition() > repetitions
                    || !seen.computeIfAbsent(result.taskId(), ignored -> new HashSet<>())
                    .add(result.repetition())) {
                throw new IllegalArgumentException("结果任务与重复序号不唯一或超出范围");
            }
            taskPassed.merge(result.taskId(), result.passed(), Boolean::logicalAnd);
            if (!result.passed()) {
                actualFailedExecutions++;
            }
            if (result.firstTokenAt().isPresent()) {
                actualTtftCount++;
            }
        }
        int actualPassedTasks = (int) taskPassed.values().stream()
                .filter(Boolean::booleanValue)
                .count();
        double actualPassK = (double) actualPassedTasks / taskCount;
        Map<String, TaskMetrics> metricsByTask = new HashMap<>();
        for (TaskMetrics metric : taskMetrics) {
            if (metricsByTask.putIfAbsent(metric.taskId(), metric) != null) {
                throw new IllegalArgumentException("逐任务指标 ID 必须唯一");
            }
            List<BenchmarkTaskResult> taskResults = results.stream()
                    .filter(result -> result.taskId().equals(metric.taskId()))
                    .toList();
            long passed = taskResults.stream().filter(BenchmarkTaskResult::passed).count();
            List<String> failures = taskResults.stream()
                    .filter(result -> !result.passed())
                    .map(BenchmarkTaskResult::failureStack)
                    .toList();
            if (metric.passedCount() != passed
                    || metric.failedCount() != failures.size()
                    || !metric.failureStacks().equals(failures)) {
                throw new IllegalArgumentException("逐任务指标与结果不一致: " + metric.taskId());
            }
        }
        if (taskPassed.size() != taskCount
                || !metricsByTask.keySet().equals(taskPassed.keySet())
                || seen.values().stream().anyMatch(values -> values.size() != repetitions)
                || actualPassedTasks != passedTaskCount
                || Double.compare(actualPassK, passK) != 0
                || actualFailedExecutions != failedExecutionCount
                || actualTtftCount != ttftCount) {
            throw new IllegalArgumentException("报告聚合字段与结果不一致");
        }
    }

    /** 单个任务在 k 次重复中的通过、失败和失败证据。 */
    public record TaskMetrics(
            String taskId,
            int passedCount,
            int failedCount,
            List<String> failureStacks) {

        public TaskMetrics {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId 不能为空");
            }
            if (passedCount < 0 || failedCount < 0) {
                throw new IllegalArgumentException("逐任务通过和失败次数不能为负数");
            }
            failureStacks = List.copyOf(Objects.requireNonNull(
                    failureStacks, "failureStacks 不能为空"));
            if (failureStacks.size() != failedCount
                    || failureStacks.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("failureStacks 必须与失败次数一致");
            }
        }
    }

    /** 首字延迟统计，单位为毫秒。 */
    public record TtftMetrics(
            int count,
            double averageMs,
            double p50Ms,
            double p95Ms,
            double maxMs) {

        public TtftMetrics {
            if (count < 0) {
                throw new IllegalArgumentException("TTFT count 不能为负数");
            }
            if (!Double.isFinite(averageMs) || !Double.isFinite(p50Ms)
                    || !Double.isFinite(p95Ms) || !Double.isFinite(maxMs)
                    || averageMs < 0 || p50Ms < 0 || p95Ms < 0 || maxMs < 0) {
                throw new IllegalArgumentException("TTFT 指标必须是有限非负数");
            }
            if (count == 0 && (averageMs != 0 || p50Ms != 0 || p95Ms != 0 || maxMs != 0)) {
                throw new IllegalArgumentException("空 TTFT 统计必须全部为 0");
            }
        }
    }
}
