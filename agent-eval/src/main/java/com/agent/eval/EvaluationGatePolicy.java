package com.agent.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

/** 评测 Suite 的全局 CI 质量门禁。 */
public record EvaluationGatePolicy(
        double minPassK,
        Duration maxTtftP95,
        BigDecimal maxCostUsd,
        int maxFailureCount) {

    public EvaluationGatePolicy {
        if (!Double.isFinite(minPassK) || minPassK < 0 || minPassK > 1) {
            throw new IllegalArgumentException("minPassK 必须位于 0 到 1 之间");
        }
        Objects.requireNonNull(maxTtftP95, "maxTtftP95 不能为空");
        if (maxTtftP95.isZero() || maxTtftP95.isNegative()) {
            throw new IllegalArgumentException("maxTtftP95 必须大于 0");
        }
        Objects.requireNonNull(maxCostUsd, "maxCostUsd 不能为空");
        if (maxCostUsd.signum() <= 0) {
            throw new IllegalArgumentException("maxCostUsd 必须大于 0");
        }
        maxCostUsd = maxCostUsd.setScale(4, RoundingMode.HALF_UP);
        if (maxFailureCount < 0) {
            throw new IllegalArgumentException("maxFailureCount 不能为负数");
        }
    }
}
