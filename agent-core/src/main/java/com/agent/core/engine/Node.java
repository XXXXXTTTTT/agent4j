package com.agent.core.engine;

/**
 * 图中的状态转换节点。
 */
@FunctionalInterface
public interface Node {

    /**
     * 根据输入状态执行节点并返回新状态。
     *
     * @param state 输入状态
     * @return 节点产生的新状态
     * @throws Exception 节点执行异常
     */
    AgentState execute(AgentState state) throws Exception;
}
