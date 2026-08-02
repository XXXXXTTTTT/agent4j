package com.agent.core.engine;

import java.util.Objects;

/**
 * 图中的状态转换节点。
 */
@FunctionalInterface
public interface Node {

    /**
     * 根据运行上下文与输入状态执行节点。
     *
     * @param context 节点执行上下文
     * @param state 输入状态
     * @return 节点产生的新状态
     * @throws Exception 节点执行异常
     */
    default AgentState execute(NodeExecutionContext context, AgentState state)
            throws Exception {
        Objects.requireNonNull(context, "context 不能为空");
        return execute(state);
    }

    /**
     * 根据输入状态执行节点并返回新状态。
     *
     * @param state 输入状态
     * @return 节点产生的新状态
     * @throws Exception 节点执行异常
     */
    AgentState execute(AgentState state) throws Exception;
}
