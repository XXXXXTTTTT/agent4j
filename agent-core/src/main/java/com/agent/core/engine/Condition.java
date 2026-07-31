package com.agent.core.engine;

/**
 * 根据最新状态选择路由键的条件函数。
 */
@FunctionalInterface
public interface Condition {

    /**
     * 计算条件路由键。
     *
     * @param state 最新状态
     * @return 已注册的路由键
     */
    String route(AgentState state);
}
