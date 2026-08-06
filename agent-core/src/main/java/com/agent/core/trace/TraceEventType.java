package com.agent.core.trace;

/** Run Trace 事件类型。 */
public enum TraceEventType {
    NODE_STARTED,
    NODE_PROGRESS,
    NODE_COMPLETED,
    INTERRUPTED,
    APPROVED,
    REJECTED,
    FAILED,
    COMPLETED
}
