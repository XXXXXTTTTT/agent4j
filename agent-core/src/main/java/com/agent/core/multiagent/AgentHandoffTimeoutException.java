package com.agent.core.multiagent;

import java.time.Duration;
import java.util.UUID;

/** 子运行超过 Handoff 单次超时。 */
public final class AgentHandoffTimeoutException extends RuntimeException {

    private final UUID taskId;
    private final UUID childRunId;
    private final Duration timeout;

    public AgentHandoffTimeoutException(UUID taskId, UUID childRunId, Duration timeout) {
        super("Agent Handoff 超时: taskId=" + taskId + ", childRunId=" + childRunId);
        this.taskId = taskId;
        this.childRunId = childRunId;
        this.timeout = timeout;
    }

    public UUID taskId() {
        return taskId;
    }

    public UUID childRunId() {
        return childRunId;
    }

    public Duration timeout() {
        return timeout;
    }
}
