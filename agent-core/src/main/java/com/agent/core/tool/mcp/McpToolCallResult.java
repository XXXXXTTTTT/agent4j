package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** MCP tools/call 返回的不可变 content 结果。 */
public record McpToolCallResult(
        JsonNode content,
        boolean isError) {

    public McpToolCallResult {
        content = Objects.requireNonNull(content, "MCP content 不能为空").deepCopy();
        if (!content.isArray()) {
            throw new IllegalArgumentException("MCP content 必须是 JSON array");
        }
    }

    @Override
    public JsonNode content() {
        return content.deepCopy();
    }
}
