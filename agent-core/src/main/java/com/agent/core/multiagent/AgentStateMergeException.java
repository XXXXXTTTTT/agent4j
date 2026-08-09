package com.agent.core.multiagent;

/** 父子状态键存在不允许静默覆盖的冲突。 */
public final class AgentStateMergeException extends IllegalStateException {

    private final String stateKey;

    public AgentStateMergeException(String stateKey) {
        super("父状态键存在不同值，拒绝覆盖: " + stateKey);
        this.stateKey = stateKey;
    }

    public String stateKey() {
        return stateKey;
    }
}
