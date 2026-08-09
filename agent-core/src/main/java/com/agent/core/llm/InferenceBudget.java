package com.agent.core.llm;

import java.time.Duration;
import java.util.Objects;

/** 单个模型端点的进程内准入预算。 */
public record InferenceBudget(
        int maxConcurrentRequests,
        int maxRequestsPerMinute,
        Duration queueTimeout) {

    /** 校验预算边界。 */
    public InferenceBudget {
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("maxConcurrentRequests 必须大于 0");
        }
        if (maxRequestsPerMinute <= 0) {
            throw new IllegalArgumentException("maxRequestsPerMinute 必须大于 0");
        }
        Objects.requireNonNull(queueTimeout, "queueTimeout 不能为空");
        if (queueTimeout.isNegative()) {
            throw new IllegalArgumentException("queueTimeout 不能为负数");
        }
    }

    /** 返回兼容已有端点的不限制预算。 */
    public static InferenceBudget unlimited() {
        return new InferenceBudget(
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Duration.ZERO);
    }
}
