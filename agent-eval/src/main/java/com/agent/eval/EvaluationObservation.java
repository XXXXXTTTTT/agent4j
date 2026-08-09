package com.agent.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/** 单次 Benchmark 执行的脱敏遥测和轨迹证据。 */
public record EvaluationObservation(
        String taskId,
        int repetition,
        List<String> trace,
        long inputTokens,
        long outputTokens,
        BigDecimal costUsd,
        FailureCategory failureCategory,
        String failureDetail) {

    public EvaluationObservation {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (repetition < 1) {
            throw new IllegalArgumentException("repetition 必须大于 0");
        }
        trace = List.copyOf(Objects.requireNonNull(trace, "trace 不能为空"));
        if (trace.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("trace 只能包含非空事件名");
        }
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token 数量不能为负数");
        }
        Objects.requireNonNull(costUsd, "costUsd 不能为空");
        if (costUsd.signum() < 0) {
            throw new IllegalArgumentException("costUsd 不能为负数");
        }
        costUsd = costUsd.setScale(4, RoundingMode.HALF_UP);
        failureCategory = Objects.requireNonNull(failureCategory,
                "failureCategory 不能为空");
        failureDetail = failureDetail == null ? "" : failureDetail.trim();
        if (failureCategory == FailureCategory.NONE && !failureDetail.isEmpty()) {
            throw new IllegalArgumentException("NONE 失败分类不能携带 failureDetail");
        }
        if (failureCategory != FailureCategory.NONE && failureDetail.isEmpty()) {
            throw new IllegalArgumentException("失败观测必须携带 failureDetail");
        }
        if (failureDetail.contains("\r") || failureDetail.contains("\n")
                || failureDetail.contains("Bearer ")
                || failureDetail.matches(".*(?i)sk-[a-z0-9].*")) {
            throw new IllegalArgumentException("failureDetail 必须是脱敏单行摘要");
        }
    }
}
