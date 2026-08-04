package com.agent.core.observability;

import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.llm.TaskType;

import java.util.Objects;
import java.util.Optional;

/** 一次模型端点调用尝试的起始上下文。 */
public record ModelCallStart(
        Optional<NodeExecutionContext> nodeContext,
        TaskType taskType,
        String endpointName,
        String requestedModel) {

    /** 校验模型调用起始上下文。 */
    public ModelCallStart {
        Objects.requireNonNull(nodeContext, "nodeContext 不能为空");
        Objects.requireNonNull(taskType, "taskType 不能为空");
        endpointName = requireText(endpointName, "endpointName");
        requestedModel = requireText(requestedModel, "requestedModel");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
