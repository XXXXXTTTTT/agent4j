package com.agent.core.multiagent;

/** Handoff 因目录、深度、次数或访问环约束被拒绝。 */
public final class AgentHandoffDeniedException extends IllegalStateException {

    private final String fromAgent;
    private final String toAgent;

    public AgentHandoffDeniedException(String fromAgent, String toAgent, String reason) {
        super(reason);
        this.fromAgent = fromAgent;
        this.toAgent = toAgent;
    }

    public String fromAgent() {
        return fromAgent;
    }

    public String toAgent() {
        return toAgent;
    }
}
