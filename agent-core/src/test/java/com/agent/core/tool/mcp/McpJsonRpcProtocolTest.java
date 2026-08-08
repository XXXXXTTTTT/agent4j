package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpJsonRpcProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesRequestWithExactJsonRpcFields() throws Exception {
        McpJsonRpcRequest request = McpJsonRpcRequest.request(
                "7",
                "tools/list",
                objectMapper.createObjectNode().put("cursor", "next"));

        JsonNode json = request.toJson(objectMapper);

        assertThat(json).isEqualTo(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":"7","method":"tools/list",
                 "params":{"cursor":"next"}}
                """));
    }

    @Test
    void omitsIdForNotification() {
        McpJsonRpcRequest request = McpJsonRpcRequest.notification(
                "notifications/initialized", JsonNodeFactory.instance.objectNode());

        assertThat(request.toJson(objectMapper).has("id")).isFalse();
    }

    @Test
    void deepCopiesRequestParams() {
        var params = objectMapper.createObjectNode().put("value", "before");
        McpJsonRpcRequest request = McpJsonRpcRequest.request("1", "test", params);

        params.put("value", "after");

        assertThat(request.toJson(objectMapper).get("params").get("value").textValue())
                .isEqualTo("before");
    }

    @Test
    void parsesSuccessResponseAndChecksExpectedId() throws Exception {
        McpJsonRpcResponse response = McpJsonRpcResponse.parse(
                objectMapper,
                "{\"jsonrpc\":\"2.0\",\"id\":\"7\",\"result\":{\"ok\":true}}",
                "7");

        assertThat(response.id()).isEqualTo("7");
        assertThat(response.result()).contains(objectMapper.readTree("{\"ok\":true}"));
        assertThat(response.error()).isEmpty();
    }

    @Test
    void parsesErrorResponseWithOptionalData() throws Exception {
        McpJsonRpcResponse response = McpJsonRpcResponse.parse(
                objectMapper,
                "{\"jsonrpc\":\"2.0\",\"id\":\"8\",\"error\":"
                        + "{\"code\":-32601,\"message\":\"missing\",\"data\":{\"x\":1}}}",
                "8");

        assertThat(response.result()).isEmpty();
        assertThat(response.error()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(-32601);
            assertThat(error.message()).isEqualTo("missing");
            assertThat(error.data()).contains(objectMapper.readTree("{\"x\":1}"));
        });
    }

    @Test
    void rejectsDuplicateFieldsAndTrailingJson() {
        assertThatThrownBy(() -> McpJsonRpcResponse.parse(
                objectMapper,
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"id\":\"2\","
                        + "\"result\":{}}",
                "1"))
                .isInstanceOf(McpProtocolException.class);

        assertThatThrownBy(() -> McpJsonRpcResponse.parse(
                objectMapper,
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}} {}",
                "1"))
                .isInstanceOf(McpProtocolException.class);
    }

    @Test
    void rejectsInvalidResultErrorShapeAndMismatchedId() {
        assertThatThrownBy(() -> McpJsonRpcResponse.parse(
                objectMapper,
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{},"
                        + "\"error\":{\"code\":1,\"message\":\"bad\"}}",
                "1"))
                .isInstanceOf(McpProtocolException.class);

        assertThatThrownBy(() -> McpJsonRpcResponse.parse(
                objectMapper,
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"message\":\"bad\"}}",
                "1"))
                .isInstanceOf(McpProtocolException.class);

        assertThatThrownBy(() -> McpJsonRpcResponse.parse(
                objectMapper,
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{}}",
                "1"))
                .isInstanceOf(McpProtocolException.class);
    }

    @Test
    void rejectsFractionalJsonRpcErrorCodeInsteadOfTruncatingIt() {
        assertThatThrownBy(() -> McpJsonRpcResponse.parse(
                objectMapper,
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":"
                        + "{\"code\":1.5,\"message\":\"bad\"}}",
                "1"))
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("code/message");
    }
}
