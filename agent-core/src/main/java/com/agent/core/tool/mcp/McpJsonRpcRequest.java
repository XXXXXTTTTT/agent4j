package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** MCP JSON-RPC 2.0 请求或通知。 */
public record McpJsonRpcRequest(
        String id,
        String method,
        JsonNode params) {

    public McpJsonRpcRequest {
        if (id != null && id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空字符串");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method 不能为空");
        }
        params = Objects.requireNonNull(params, "params 不能为空").deepCopy();
        if (!params.isObject() && !params.isArray()) {
            throw new IllegalArgumentException("params 必须是 JSON object 或 array");
        }
    }

    /** 创建带字符串 ID 的请求。 */
    public static McpJsonRpcRequest request(
            String id,
            String method,
            JsonNode params) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("request id 不能为空");
        }
        return new McpJsonRpcRequest(id, method, params);
    }

    /** 创建不等待响应的通知。 */
    public static McpJsonRpcRequest notification(
            String method,
            JsonNode params) {
        return new McpJsonRpcRequest(null, method, params);
    }

    /** 将请求序列化为独立 JSON 节点。 */
    public JsonNode toJson(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        var object = objectMapper.createObjectNode();
        object.put("jsonrpc", "2.0");
        if (id != null) {
            object.put("id", id);
        }
        object.put("method", method);
        object.set("params", params.deepCopy());
        return object;
    }
}
