package com.agent.core.tool.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** MCP JSON-RPC 2.0 响应，严格区分成功结果和协议错误。 */
public record McpJsonRpcResponse(
        String id,
        Optional<JsonNode> result,
        Optional<McpError> error) {

    public McpJsonRpcResponse {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("response id 不能为空");
        }
        result = copyResult(Objects.requireNonNull(result, "result 不能为空"));
        error = Objects.requireNonNull(error, "error 不能为空");
        if (result.isPresent() == error.isPresent()) {
            throw new IllegalArgumentException("result 与 error 必须二选一");
        }
    }

    /** 解析并严格校验单个 JSON-RPC 响应。 */
    public static McpJsonRpcResponse parse(
            ObjectMapper objectMapper,
            String json,
            String expectedId) {
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        if (json == null || json.isBlank()) {
            throw new McpProtocolException("MCP 响应不能为空");
        }
        if (expectedId == null || expectedId.isBlank()) {
            throw new IllegalArgumentException("expectedId 不能为空");
        }
        try {
            JsonNode root = objectMapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            return fromNode(root, expectedId);
        } catch (McpProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new McpProtocolException("MCP JSON-RPC 响应解析失败", exception);
        }
    }

    private static McpJsonRpcResponse fromNode(JsonNode root, String expectedId) {
        if (root == null || !root.isObject()) {
            throw new McpProtocolException("MCP 响应必须是 JSON object");
        }
        Set<String> fields = new HashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        if (!root.has("jsonrpc") || !root.has("id")
                || (!root.has("result") && !root.has("error"))
                || (root.has("result") && root.has("error"))) {
            throw new McpProtocolException("MCP 响应字段不符合 JSON-RPC 2.0");
        }
        Set<String> expectedFields = root.has("result")
                ? Set.of("jsonrpc", "id", "result")
                : Set.of("jsonrpc", "id", "error");
        if (!fields.equals(expectedFields)) {
            throw new McpProtocolException("MCP 响应包含未知字段");
        }
        if (!root.get("jsonrpc").isTextual()
                || !"2.0".equals(root.get("jsonrpc").textValue())) {
            throw new McpProtocolException("MCP jsonrpc 必须精确为 2.0");
        }
        if (!root.get("id").isTextual()
                || !expectedId.equals(root.get("id").textValue())) {
            throw new McpProtocolException("MCP 响应 ID 不匹配");
        }
        if (root.has("result")) {
            return new McpJsonRpcResponse(
                    expectedId,
                    Optional.of(root.get("result").deepCopy()),
                    Optional.empty());
        }
        return new McpJsonRpcResponse(
                expectedId,
                Optional.empty(),
                Optional.of(parseError(root.get("error"))));
    }

    private static McpError parseError(JsonNode node) {
        if (node == null || !node.isObject()
                || !node.has("code") || !node.has("message")) {
            throw new McpProtocolException("MCP error 必须包含 code 和 message");
        }
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!fields.stream().allMatch(field -> Set.of("code", "message", "data").contains(field))) {
            throw new McpProtocolException("MCP error 包含未知字段");
        }
        if (!node.get("code").canConvertToInt() || !node.get("message").isTextual()
                || node.get("message").textValue().isBlank()) {
            throw new McpProtocolException("MCP error 的 code/message 类型不合法");
        }
        Optional<JsonNode> data = node.has("data")
                ? Optional.of(node.get("data").deepCopy())
                : Optional.empty();
        return new McpError(node.get("code").intValue(), node.get("message").textValue(), data);
    }

    private static Optional<JsonNode> copyResult(Optional<JsonNode> value) {
        return value.map(node -> Objects.requireNonNull(node, "result 节点不能为空").deepCopy());
    }

    /** JSON-RPC error 对象。 */
    public record McpError(
            int code,
            String message,
            Optional<JsonNode> data) {

        public McpError {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("error message 不能为空");
            }
            data = Objects.requireNonNull(data, "error data 不能为空")
                    .map(node -> Objects.requireNonNull(node, "error data 节点不能为空").deepCopy());
        }
    }
}
