package com.agent.core.multiagent;

/** 子状态投影或所有权校验失败。 */
public final class AgentHandoffStateException extends IllegalStateException {

    private final String stateKey;

    public AgentHandoffStateException(String stateKey, String message) {
        super(message);
        this.stateKey = stateKey;
    }

    public String stateKey() {
        return stateKey;
    }
}
