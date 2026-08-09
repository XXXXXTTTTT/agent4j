package com.agent.core.multiagent;

import java.util.UUID;

/** 未持久化子运行请求了 5A 不支持的嵌套 HITL。 */
public final class AgentHandoffInterruptedException extends RuntimeException {

    private final UUID taskId;
    private final UUID childRunId;
    private final String nodeName;

    public AgentHandoffInterruptedException(
            UUID taskId,
            UUID childRunId,
            String nodeName) {
        super("子运行请求嵌套 HITL: childRunId=" + childRunId + ", nodeName=" + nodeName);
        this.taskId = taskId;
        this.childRunId = childRunId;
        this.nodeName = nodeName;
    }

    public UUID taskId() {
        return taskId;
    }

    public UUID childRunId() {
        return childRunId;
    }

    public String nodeName() {
        return nodeName;
    }
}
