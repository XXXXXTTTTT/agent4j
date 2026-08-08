package com.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** 工具执行的不可变结构化结果。 */
public record ToolResult(
        String callId,
        String name,
        ToolResultStatus status,
        JsonNode output,
        String errorStack,
        long durationMs) {

    /** 校验结果状态并复制输出节点。 */
    public ToolResult {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId 不能为空");
        }
        ToolDefinition.requireName(name);
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(output, "output 不能为空");
        Objects.requireNonNull(errorStack, "errorStack 不能为空");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs 不能为负数");
        }
        if (status == ToolResultStatus.SUCCEEDED) {
            if (!output.isObject() && !output.isArray()) {
                throw new IllegalArgumentException("SUCCEEDED 的 output 必须是 object 或 array");
            }
            if (!errorStack.isEmpty()) {
                throw new IllegalArgumentException("SUCCEEDED 的 errorStack 必须为空");
            }
        } else {
            if (!output.isNull()) {
                throw new IllegalArgumentException("非成功结果的 output 必须是 JSON null");
            }
            if (errorStack.isBlank()) {
                throw new IllegalArgumentException("非成功结果的 errorStack 不能为空");
            }
        }
        output = output.deepCopy();
    }

    /** 返回输出的独立副本。 */
    @Override
    public JsonNode output() {
        return output.deepCopy();
    }
}
