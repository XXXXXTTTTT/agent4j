package com.agent.core.engine;

/** Agent Run 的持久化生命周期状态。 */
public enum RunStatus {
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    REJECTED,
    FAILED
}
