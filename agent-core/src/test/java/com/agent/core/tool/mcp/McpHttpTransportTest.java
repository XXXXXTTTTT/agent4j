package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpHttpTransportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private McpHttpTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
        server.stop(0);
    }

    @Test
    void postsJsonRpcRequestAndParsesJsonResponse() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server.createContext("/mcp", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            write(exchange, 200, "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}");
        });
        transport = transport(Duration.ofSeconds(2));

        McpJsonRpcResponse response = transport.request(
                McpJsonRpcRequest.request(
                        "1", "initialize", objectMapper.createObjectNode()));

        assertThat(objectMapper.readTree(body.get()))
                .isEqualTo(objectMapper.readTree(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"1\","
                                + "\"method\":\"initialize\",\"params\":{}}"));
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(response.result()).isPresent();
    }

    @Test
    void sendsNotificationWithoutParsingResponse() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/mcp", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            write(exchange, 202, "application/json", "");
        });
        transport = transport(Duration.ofSeconds(2));

        transport.notify(McpJsonRpcRequest.notification(
                "notifications/initialized", objectMapper.createObjectNode()));

        assertThat(objectMapper.readTree(body.get()).has("id")).isFalse();
    }

    @Test
    void rejectsNonSuccessfulHttpStatus() {
        server.createContext("/mcp", exchange -> write(
                exchange, 503, "application/json", "{\"error\":\"offline\"}"));
        transport = transport(Duration.ofSeconds(2));

        assertThatThrownBy(() -> transport.request(request()))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("503");
    }

    @Test
    void rejectsEmptyAndSseResponses() {
        server.createContext("/mcp", exchange -> write(exchange, 200, "application/json", ""));
        transport = transport(Duration.ofSeconds(2));

        assertThatThrownBy(() -> transport.request(request()))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("空");

        server.removeContext("/mcp");
        server.createContext("/mcp", exchange -> write(
                exchange, 200, "text/event-stream", "data: {}\n\n"));

        assertThatThrownBy(() -> transport.request(request()))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("content type");
    }

    @Test
    void cancelsRequestWhenTimeoutExpires() {
        server.createContext("/mcp", exchange -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            write(exchange, 200, "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}");
        });
        transport = transport(Duration.ofMillis(100));

        assertThatThrownBy(() -> transport.request(request()))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("超时");
    }

    private McpJsonRpcRequest request() {
        return McpJsonRpcRequest.request("1", "tools/list", objectMapper.createObjectNode());
    }

    private McpHttpTransport transport(Duration timeout) {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        return new McpHttpTransport(
                RestClient.builder().build(), objectMapper, endpoint, timeout);
    }

    private static void write(
            HttpExchange exchange,
            int status,
            String contentType,
            String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
