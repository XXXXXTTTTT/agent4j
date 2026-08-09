package com.agent.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 能力级评测聚合报告。 */
public record EvaluationReport(
        String suiteId,
        BenchmarkReport benchmarkReport,
        EvaluationGatePolicy policy,
        List<CapabilityMetrics> capabilities,
        long totalInputTokens,
        long totalOutputTokens,
        BigDecimal totalCostUsd,
        Map<FailureCategory, Integer> failureCounts,
        Instant generatedAt) {

    public EvaluationReport {
        if (suiteId == null || suiteId.isBlank()) {
            throw new IllegalArgumentException("suiteId 不能为空");
        }
        benchmarkReport = Objects.requireNonNull(benchmarkReport, "benchmarkReport 不能为空");
        policy = Objects.requireNonNull(policy, "policy 不能为空");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities 不能为空"));
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities 不能为空");
        }
        if (totalInputTokens < 0 || totalOutputTokens < 0) {
            throw new IllegalArgumentException("token 总数不能为负数");
        }
        Objects.requireNonNull(totalCostUsd, "totalCostUsd 不能为空");
        if (totalCostUsd.signum() < 0) {
            throw new IllegalArgumentException("totalCostUsd 不能为负数");
        }
        totalCostUsd = totalCostUsd.setScale(4, RoundingMode.HALF_UP);
        failureCounts = Map.copyOf(Objects.requireNonNull(
                failureCounts, "failureCounts 不能为空"));
        if (failureCounts.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null
                        || entry.getValue() < 0)) {
            throw new IllegalArgumentException("failureCounts 必须是非负计数");
        }
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt 不能为空");
    }

    /** 单个能力的稳定性和证据指标。 */
    public record CapabilityMetrics(
            String capabilityId,
            int taskCount,
            int passedTaskCount,
            double passK,
            int executionCount,
            int tracePassedCount,
            double requiredMinPassK,
            Duration maxTtftP95,
            BigDecimal totalCostUsd,
            Duration ttftP95,
            List<String> failedTaskIds) {

        public CapabilityMetrics {
            if (capabilityId == null || capabilityId.isBlank()) {
                throw new IllegalArgumentException("capabilityId 不能为空");
            }
            if (taskCount < 1 || passedTaskCount < 0 || passedTaskCount > taskCount
                    || executionCount < 1 || tracePassedCount < 0
                    || tracePassedCount > executionCount) {
                throw new IllegalArgumentException("能力指标计数非法");
            }
            if (!Double.isFinite(passK) || passK < 0 || passK > 1) {
                throw new IllegalArgumentException("能力 passK 必须位于 0 到 1 之间");
            }
            if (!Double.isFinite(requiredMinPassK)
                    || requiredMinPassK < 0 || requiredMinPassK > 1) {
                throw new IllegalArgumentException("能力 requiredMinPassK 必须位于 0 到 1 之间");
            }
            maxTtftP95 = Objects.requireNonNull(maxTtftP95, "maxTtftP95 不能为空");
            if (maxTtftP95.isZero() || maxTtftP95.isNegative()) {
                throw new IllegalArgumentException("maxTtftP95 必须大于 0");
            }
            totalCostUsd = Objects.requireNonNull(totalCostUsd, "totalCostUsd 不能为空")
                    .setScale(4, RoundingMode.HALF_UP);
            if (totalCostUsd.signum() < 0) {
                throw new IllegalArgumentException("能力 totalCostUsd 不能为负数");
            }
            ttftP95 = Objects.requireNonNull(ttftP95, "ttftP95 不能为空");
            if (ttftP95.isNegative()) {
                throw new IllegalArgumentException("ttftP95 不能为负数");
            }
            failedTaskIds = List.copyOf(Objects.requireNonNull(
                    failedTaskIds, "failedTaskIds 不能为空"));
        }
    }
}
