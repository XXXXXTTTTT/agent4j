package com.agent.core.engine;

import java.time.Duration;
import java.util.Objects;

/** 单次图执行的不可变资源预算。 */
public record ExecutionBudget(
        Duration maxDuration,
        Duration idleTimeout,
        long tokenBudget,
        int maxSteps,
        int noProgressLimit) {

    /** 校验所有预算必须为正数。 */
    public ExecutionBudget {
        Objects.requireNonNull(maxDuration, "maxDuration 不能为空");
        Objects.requireNonNull(idleTimeout, "idleTimeout 不能为空");
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration 必须大于 0");
        }
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout 必须大于 0");
        }
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget 必须大于 0");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps 必须大于 0");
        }
        if (noProgressLimit <= 0) {
            throw new IllegalArgumentException("noProgressLimit 必须大于 0");
        }
    }
}
