package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** MCP client，负责握手、工具发现和远程工具调用协议。 */
public final class McpClient implements AutoCloseable {

    private final McpTransport transport;
    private final ObjectMapper objectMapper;
    private final String protocolVersion;
    private final String clientName;
    private final String clientVersion;
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private boolean initialized;

    public McpClient(
            McpTransport transport,
            ObjectMapper objectMapper,
            String protocolVersion,
            String clientName,
            String clientVersion) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.protocolVersion = requireText(protocolVersion, "protocolVersion");
        this.clientName = requireText(clientName, "clientName");
        this.clientVersion = requireText(clientVersion, "clientVersion");
    }

    /** 完成一次 MCP 初始化握手；重复调用保持幂等。 */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        var params = objectMapper.createObjectNode();
        params.put("protocolVersion", protocolVersion);
        params.set("capabilities", objectMapper.createObjectNode());
        params.set("clientInfo", objectMapper.createObjectNode()
                .put("name", clientName)
                .put("version", clientVersion));
        JsonNode result = requireResult(transport.request(
                McpJsonRpcRequest.request(nextId(), "initialize", params)));
        requireInitializeResult(result);
        transport.notify(McpJsonRpcRequest.notification(
                "notifications/initialized", objectMapper.createObjectNode()));
        initialized = true;
    }

    /** 发现所有远程工具并处理 MCP cursor 分页。 */
    public synchronized List<McpRemoteTool> listTools() {
        requireInitialized();
        List<McpRemoteTool> tools = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            var params = objectMapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
                if (!cursors.add(cursor)) {
                    throw new McpProtocolException("MCP tools/list cursor 重复");
                }
            }
            JsonNode result = requireResult(transport.request(
                    McpJsonRpcRequest.request(nextId(), "tools/list", params)));
            if (!result.isObject() || !result.has("tools")) {
                throw new McpProtocolException("MCP tools/list result 缺少 tools");
            }
            Set<String> fields = new HashSet<>();
            result.fieldNames().forEachRemaining(fields::add);
            if (!fields.stream().allMatch(field -> Set.of("tools", "nextCursor").contains(field))) {
                throw new McpProtocolException("MCP tools/list result 包含未知字段");
            }
            JsonNode toolsNode = result.get("tools");
            if (!toolsNode.isArray()) {
                throw new McpProtocolException("MCP tools/list tools 必须是数组");
            }
            for (JsonNode toolNode : toolsNode) {
                McpRemoteTool tool = parseTool(toolNode);
                if (!names.add(tool.name())) {
                    throw new McpProtocolException("MCP tools/list 包含重复工具: " + tool.name());
                }
                tools.add(tool);
            }
            cursor = result.has("nextCursor")
                    ? text(result.get("nextCursor"), "nextCursor")
                    : null;
        } while (cursor != null);
        return List.copyOf(tools);
    }

    /** 调用一个远程工具，保留 content 与 isError 结构。 */
    public synchronized McpToolCallResult callTool(String name, JsonNode arguments) {
        requireInitialized();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP 工具 name 不能为空");
        }
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("MCP 工具 arguments 必须是 JSON object");
        }
        var params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments.deepCopy());
        JsonNode result = requireResult(transport.request(
                McpJsonRpcRequest.request(nextId(), "tools/call", params)));
        if (!result.isObject() || !result.has("content") || !result.has("isError")) {
            throw new McpProtocolException("MCP tools/call result 字段不完整");
        }
        Set<String> fields = new HashSet<>();
        result.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("content", "isError"))) {
            throw new McpProtocolException("MCP tools/call result 包含未知字段");
        }
        if (!result.get("content").isArray() || !result.get("isError").isBoolean()) {
            throw new McpProtocolException("MCP tools/call result 类型不合法");
        }
        return new McpToolCallResult(result.get("content"), result.get("isError").booleanValue());
    }

    @Override
    public void close() {
        transport.close();
    }

    private McpRemoteTool parseTool(JsonNode node) {
        if (node == null || !node.isObject()
                || !node.has("name") || !node.has("description") || !node.has("inputSchema")) {
            throw new McpProtocolException("MCP tool 定义字段不完整");
        }
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("name", "description", "inputSchema"))) {
            throw new McpProtocolException("MCP tool 定义包含未知字段");
        }
        try {
            return new McpRemoteTool(
                    text(node.get("name"), "tool name"),
                    text(node.get("description"), "tool description"),
                    node.get("inputSchema"));
        } catch (IllegalArgumentException exception) {
            throw new McpProtocolException("MCP tool 定义不合法", exception);
        }
    }

    private JsonNode requireResult(McpJsonRpcResponse response) {
        Objects.requireNonNull(response, "MCP response 不能为空");
        if (response.error().isPresent()) {
            McpJsonRpcResponse.McpError error = response.error().get();
            throw new McpProtocolException(
                    "MCP 远程错误 code=" + error.code() + " message=" + error.message());
        }
        return response.result().orElseThrow(
                () -> new McpProtocolException("MCP response 缺少 result"));
    }

    private void requireInitializeResult(JsonNode result) {
        if (!result.isObject()
                || !result.has("protocolVersion")
                || !result.has("capabilities")
                || !result.has("serverInfo")
                || !result.get("protocolVersion").isTextual()
                || !result.get("capabilities").isObject()
                || !result.get("serverInfo").isObject()) {
            throw new McpProtocolException("MCP initialize result 字段不合法");
        }
        if (!protocolVersion.equals(result.get("protocolVersion").textValue())) {
            throw new McpProtocolException("MCP initialize protocolVersion 不匹配");
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("MCP client 尚未初始化");
        }
    }

    private String nextId() {
        return Long.toString(nextRequestId.getAndIncrement());
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new McpProtocolException(field + " 必须是非空字符串");
        }
        return node.textValue();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
