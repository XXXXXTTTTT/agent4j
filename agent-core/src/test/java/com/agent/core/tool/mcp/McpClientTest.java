package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void initializesOnceThenDiscoversToolsAndCallsRemoteTool() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(success("1", """
                {"protocolVersion":"2025-06-18","capabilities":{},
                 "serverInfo":{"name":"demo","version":"1"}}
                """));
        transport.responses.add(success("2", """
                {"tools":[{"name":"echo","description":"Echo text",
                 "inputSchema":{"type":"object","properties":{"text":{"type":"string"}},
                 "required":["text"],"additionalProperties":false}}]}
                """));
        transport.responses.add(success("3", """
                {"content":[{"type":"text","text":"hello"}],"isError":false}
                """));
        McpClient client = client(transport);

        client.initialize();
        client.initialize();
        List<McpRemoteTool> tools = client.listTools();
        McpToolCallResult result = client.callTool(
                "echo", objectMapper.createObjectNode().put("text", "hello"));

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("echo");
            assertThat(tool.inputSchema().get("type").textValue()).isEqualTo("object");
        });
        assertThat(result.isError()).isFalse();
        assertThat(result.content().get(0).get("text").textValue()).isEqualTo("hello");
        assertThat(transport.requests).extracting(McpJsonRpcRequest::method)
                .containsExactly("initialize", "tools/list", "tools/call");
        assertThat(transport.notifications).extracting(McpJsonRpcRequest::method)
                .containsExactly("notifications/initialized");
    }

    @Test
    void rejectsUseBeforeInitialization() {
        McpClient client = client(new FakeTransport());

        assertThatThrownBy(client::listTools)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("初始化");
        assertThatThrownBy(() -> client.callTool(
                "echo", objectMapper.createObjectNode()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("初始化");
    }

    @Test
    void rejectsDuplicateOrMalformedDiscoveredTools() {
        FakeTransport transport = initializedTransport("""
                {"tools":[
                  {"name":"echo","description":"one","inputSchema":{"type":"object"}},
                  {"name":"echo","description":"two","inputSchema":{"type":"object"}}
                ]}
                """);
        McpClient client = client(transport);
        client.initialize();

        assertThatThrownBy(client::listTools)
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void preservesRemoteIsErrorResultAndRejectsNonObjectArguments() {
        FakeTransport transport = initializedTransport("{\"tools\":[]}");
        transport.responses.add(success("3", """
                {"content":[{"type":"text","text":"remote failure"}],"isError":true}
                """));
        McpClient client = client(transport);
        client.initialize();
        client.listTools();

        McpToolCallResult result = client.callTool(
                "echo", objectMapper.createObjectNode().put("value", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content().get(0).get("text").textValue())
                .isEqualTo("remote failure");
        assertThatThrownBy(() -> client.callTool("echo", objectMapper.createArrayNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object");
    }

    @Test
    void preservesProtocolErrorCauseFromRemoteServer() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new McpJsonRpcResponse(
                "1", Optional.empty(), Optional.of(new McpJsonRpcResponse.McpError(
                        -32001, "remote unavailable", Optional.empty()))));
        McpClient client = client(transport);

        assertThatThrownBy(client::initialize)
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("remote unavailable");
    }

    private McpClient client(McpTransport transport) {
        return new McpClient(transport, objectMapper, "2025-06-18", "agent4j", "0.1");
    }

    private FakeTransport initializedTransport(String toolsResult) {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(success("1", """
                {"protocolVersion":"2025-06-18","capabilities":{},
                 "serverInfo":{"name":"demo","version":"1"}}
                """));
        transport.responses.add(success("2", toolsResult));
        return transport;
    }

    private McpJsonRpcResponse success(String id, String result) {
        try {
            return new McpJsonRpcResponse(
                    id,
                    Optional.of(objectMapper.readTree(result)),
                    Optional.empty());
        } catch (Exception exception) {
            throw new AssertionError("测试响应 JSON 无效", exception);
        }
    }

    private static final class FakeTransport implements McpTransport {
        private final Deque<McpJsonRpcResponse> responses = new ArrayDeque<>();
        private final List<McpJsonRpcRequest> requests = new ArrayList<>();
        private final List<McpJsonRpcRequest> notifications = new ArrayList<>();

        @Override
        public McpJsonRpcResponse request(McpJsonRpcRequest request) {
            requests.add(request);
            McpJsonRpcResponse response = responses.pollFirst();
            if (response == null) {
                throw new AssertionError("fake MCP response 不足");
            }
            return response;
        }

        @Override
        public void notify(McpJsonRpcRequest notification) {
            notifications.add(notification);
        }
    }
}
