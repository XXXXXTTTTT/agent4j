package com.agent.core.engine;

import java.util.Optional;
import java.util.UUID;

/** 在节点执行前判断是否需要人工审批。 */
@FunctionalInterface
public interface InterruptPolicy {

    /**
     * 评估指定节点。
     *
     * @param runId    Run 标识
     * @param nodeName 节点精确名称
     * @param state    当前不可变状态
     * @return 中断请求；无需中断时为空
     */
    Optional<InterruptRequest> evaluate(UUID runId, String nodeName, AgentState state);

    /**
     * 返回永不中断的策略。
     *
     * @return 永不中断策略
     */
    static InterruptPolicy never() {
        return (runId, nodeName, state) -> Optional.empty();
    }
}
