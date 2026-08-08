package com.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** 模型或调用方产生的不可变工具调用。 */
public record ToolCall(
        String callId,
        String name,
        JsonNode arguments) {

    /** 校验调用并复制参数节点。 */
    public ToolCall {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId 不能为空");
        }
        ToolDefinition.requireName(name);
        Objects.requireNonNull(arguments, "arguments 不能为空");
        if (!arguments.isObject()) {
            throw new IllegalArgumentException("arguments 必须是 JSON object");
        }
        arguments = arguments.deepCopy();
    }

    /** 返回参数的独立副本。 */
    @Override
    public JsonNode arguments() {
        return arguments.deepCopy();
    }
}
