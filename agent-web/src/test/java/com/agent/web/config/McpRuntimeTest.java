package com.agent.web.config;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpRuntimeTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void discoversAndExecutesConfiguredMcpToolsThroughGovernedRegistry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> respond(exchange, calls));
        server.start();
        McpGatewayProperties properties = new McpGatewayProperties(
                true,
                "2025-06-18",
                "agent4j",
                "0.1.0",
                List.of(new McpGatewayProperties.Server(
                        "remote",
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                        "Bearer test-token",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        ToolRiskLevel.LOW,
                        Set.of(RequiredCapability.CODE_READ))));

        try (DefaultToolRegistry registry = new DefaultToolRegistry();
             McpRuntime runtime = McpRuntime.connect(properties, registry, new ObjectMapper())) {
            assertThat(registry.list()).extracting(definition -> definition.name())
                    .containsExactly("remote.echo");
            var result = registry.execute(
                    new ToolCall("call-1", "remote.echo",
                            new ObjectMapper().createObjectNode().put("text", "hello")),
                    new ToolInvocationContext(
                            UUID.fromString("90000000-0000-0000-0000-000000000001"),
                            "tool-agent",
                            "local",
                            Path.of("."),
                            Set.of(RequiredCapability.CODE_READ),
                            false));
            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(result.output().get(0).path("text").textValue()).isEqualTo("hello");
        }
        assertThat(calls).hasValue(4);
    }

    private void respond(HttpExchange exchange, AtomicInteger calls) throws java.io.IOException {
        calls.incrementAndGet();
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer test-token");
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        if (request.contains("\"method\":\"initialize\"")) {
            response = """
                    {"jsonrpc":"2.0","id":"1","result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}
                    """;
        } else if (request.contains("\"method\":\"tools/list\"")) {
            response = """
                    {"jsonrpc":"2.0","id":"2","result":{"tools":[{"name":"echo","description":"远程回显","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}}]}}
                    """;
        } else if (request.contains("\"method\":\"tools/call\"")) {
            response = """
                    {"jsonrpc":"2.0","id":"3","result":{"content":[{"type":"text","text":"hello"}],"isError":false}}
                    """;
        } else {
            response = "";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.isEmpty() ? 202 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
