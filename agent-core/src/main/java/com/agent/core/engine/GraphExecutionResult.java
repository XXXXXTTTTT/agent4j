package com.agent.core.engine;

import java.util.Objects;

/** 状态图正常完成或挂起的强类型结果。 */
public sealed interface GraphExecutionResult
        permits GraphExecutionResult.Completed, GraphExecutionResult.Interrupted {

    /**
     * 图已到达终点。
     *
     * @param state 最终状态
     */
    record Completed(AgentState state) implements GraphExecutionResult {

        /** 校验完成结果。 */
        public Completed {
            Objects.requireNonNull(state, "state 不能为空");
        }
    }

    /**
     * 图在节点执行前挂起。
     *
     * @param state    挂起时状态
     * @param nodeName 待执行节点精确名称
     * @param request  中断请求
     */
    record Interrupted(
            AgentState state,
            String nodeName,
            InterruptRequest request) implements GraphExecutionResult {

        /** 校验中断结果。 */
        public Interrupted {
            Objects.requireNonNull(state, "state 不能为空");
            if (nodeName == null || nodeName.isBlank()) {
                throw new IllegalArgumentException("nodeName 不能为空");
            }
            Objects.requireNonNull(request, "request 不能为空");
            if (!nodeName.equals(request.nodeName())) {
                throw new IllegalArgumentException("中断结果节点名称不一致");
            }
        }
    }
}
