package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.regex.Pattern;

/** MCP tools/list 返回的不可变远程工具描述。 */
public record McpRemoteTool(
        String name,
        String description,
        JsonNode inputSchema) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    public McpRemoteTool {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("MCP 工具 name 格式不合法");
        }
        description = description == null ? "" : description;
        inputSchema = Objects.requireNonNull(inputSchema, "MCP 工具 inputSchema 不能为空")
                .deepCopy();
        if (!inputSchema.isObject()) {
            throw new IllegalArgumentException("MCP 工具 inputSchema 必须是 JSON object");
        }
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }
}
