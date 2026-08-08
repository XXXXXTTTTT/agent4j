package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** 远程 MCP 工具返回 isError=true 时的受保留异常。 */
public final class McpRemoteToolException extends RuntimeException {

    private final String remoteName;
    private final JsonNode content;

    public McpRemoteToolException(String remoteName, JsonNode content) {
        this(remoteName, content, null);
    }

    public McpRemoteToolException(String remoteName, JsonNode content, Throwable cause) {
        super(message(remoteName, content), cause);
        if (remoteName == null || remoteName.isBlank()) {
            throw new IllegalArgumentException("remoteName 不能为空");
        }
        this.remoteName = remoteName;
        this.content = Objects.requireNonNull(content, "content 不能为空").deepCopy();
        if (!this.content.isArray()) {
            throw new IllegalArgumentException("content 必须是 JSON array");
        }
    }

    public String remoteName() {
        return remoteName;
    }

    public JsonNode content() {
        return content.deepCopy();
    }

    private static String message(String remoteName, JsonNode content) {
        return "MCP 远程工具执行失败: " + remoteName + ", content="
                + (content == null ? "null" : content.toString());
    }
}
