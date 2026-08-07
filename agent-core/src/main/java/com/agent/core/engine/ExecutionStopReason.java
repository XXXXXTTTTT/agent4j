package com.agent.core.engine;

/** 图执行预算耗尽的精确原因。 */
public enum ExecutionStopReason {
    MAX_DURATION,
    IDLE_TIMEOUT,
    TOKEN_BUDGET,
    MAX_STEPS,
    NO_PROGRESS
}
