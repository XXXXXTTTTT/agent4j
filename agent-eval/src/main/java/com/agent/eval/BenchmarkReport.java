package com.agent.eval;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Benchmark 的不可变聚合报告。 */
public record BenchmarkReport(
        List<BenchmarkTaskResult> results,
        int repetitions,
        int taskCount,
        int passedTaskCount,
        double passK,
        int failedExecutionCount,
        TtftMetrics ttft,
        Instant generatedAt) {

    public BenchmarkReport {
        results = List.copyOf(Objects.requireNonNull(results, "results 不能为空"));
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
