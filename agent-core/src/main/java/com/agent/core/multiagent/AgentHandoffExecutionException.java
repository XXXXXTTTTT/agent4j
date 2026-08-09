package com.agent.core.multiagent;

import java.util.UUID;

/** 子图执行失败并保留原始 cause。 */
public final class AgentHandoffExecutionException extends RuntimeException {

    private final UUID taskId;
    private final UUID childRunId;
    private final String toAgent;

    public AgentHandoffExecutionException(
            UUID taskId,
            UUID childRunId,
            String toAgent,
            Throwable cause) {
        super("Agent 子运行失败: toAgent=" + toAgent + ", childRunId=" + childRunId, cause);
        this.taskId = taskId;
        this.childRunId = childRunId;
        this.toAgent = toAgent;
    }

    public UUID taskId() {
        return taskId;
    }

    public UUID childRunId() {
        return childRunId;
    }

    public String toAgent() {
        return toAgent;
    }
}
