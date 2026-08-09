package com.agent.core.engine;

/** 显式定义父图与子图之间的状态投影和结果合并边界。 */
public interface SubgraphStateBridge {

    /** 将父图状态投影为子图的独立起始状态。 */
    AgentState project(AgentState parentState);

    /** 将已完成的子图状态合并回父图状态。 */
    AgentState merge(AgentState parentState, AgentState childState);
}
