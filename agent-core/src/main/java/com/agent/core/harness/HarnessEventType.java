package com.agent.core.harness;

/** Harness 可观测与治理事件类型。 */
public enum HarnessEventType {
    BEFORE_NODE,
    AFTER_NODE,
    BEFORE_TOOL,
    AFTER_TOOL,
    FAILURE,
    BUDGET_EXHAUSTED
}
