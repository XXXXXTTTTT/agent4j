package com.agent.core.multiagent;

/** 精确 Agent 标识未注册。 */
public final class AgentNotFoundException extends RuntimeException {

    private final String agentId;

    public AgentNotFoundException(String agentId) {
        super("Agent 未注册: " + agentId);
        this.agentId = agentId;
    }

    public String agentId() {
        return agentId;
    }
}
