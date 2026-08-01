package com.agent.core.engine;

import java.util.Objects;
import java.util.UUID;

/**
 * 从指定节点执行状态图的请求。
 *
 * @param runId                   Run 标识
 * @param state                   起始状态
 * @param startNode               起始节点精确名称
 * @param bypassInterruptAtStart  是否只跳过起始节点的一次中断检查
 */
public record GraphExecutionRequest(
        UUID runId,
        AgentState state,
        String startNode,
        boolean bypassInterruptAtStart) {

    /** 校验图执行请求。 */
    public GraphExecutionRequest {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        if (startNode == null || startNode.isBlank()) {
            throw new IllegalArgumentException("startNode 不能为空");
        }
    }
}
