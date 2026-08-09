package com.agent.core.multiagent;

/** 发布独立子运行 Handoff Trace。 */
@FunctionalInterface
public interface AgentHandoffEventPublisher {

    void publish(AgentHandoffEvent event);
}
