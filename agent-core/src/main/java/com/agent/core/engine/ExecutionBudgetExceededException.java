package com.agent.core.engine;

import java.util.Objects;

/** 图执行超过单项预算时抛出的可审计异常。 */
public final class ExecutionBudgetExceededException extends RuntimeException {

    private final ExecutionStopReason reason;
    private final long observed;
    private final long limit;

    /** 创建预算耗尽异常。 */
    public ExecutionBudgetExceededException(
            ExecutionStopReason reason,
            long observed,
            long limit) {
        super("图执行预算耗尽: " + Objects.requireNonNull(reason, "reason 不能为空")
                + ", observed=" + observed + ", limit=" + limit);
        if (observed < 0) {
            throw new IllegalArgumentException("observed 不能小于 0");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        this.reason = reason;
        this.observed = observed;
        this.limit = limit;
    }

    public ExecutionStopReason reason() {
        return reason;
    }

    public long observed() {
        return observed;
    }

    public long limit() {
        return limit;
    }
}
