package com.agent.core.skill;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.core.tool.mcp.McpClient;
import com.agent.core.tool.mcp.McpJsonRpcRequest;
import com.agent.core.tool.mcp.McpJsonRpcResponse;
import com.agent.core.tool.mcp.McpToolRegistryAdapter;
import com.agent.core.tool.mcp.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMcpIntegrationTest {

    private static final UUID RUN_ID = UUID.fromString("74000000-0000-0000-0000-000000000001");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mcpToolIsExposedAfterActivationButStillRequiresRegistryApproval() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        try (ToolRegistry registry = new DefaultToolRegistry()) {
            new McpToolRegistryAdapter(
                    new McpClient(transport, mapper, "2025-06-18", "skill-test", "1.0"), registry)
                    .registerDiscoveredTools("remote", ToolRiskLevel.HIGH, Set.of(), Duration.ofSeconds(1));
            SkillCatalog catalog = new SkillCatalog(List.of(new SkillDefinition(
                    "remote-advisor", "1.0.0", "远程建议", List.of("远程回显"),
                    List.of("remote.echo"), "先调用远程回显，再解释结果")), registry, mapper);

            SkillPromptContext context = catalog.resolve("请求远程回显", Set.of("remote-advisor"));
            assertThat(context.activatedSkills().getFirst().tools())
                    .extracting(SkillToolMetadata::name).containsExactly("remote.echo");

            ToolResult waiting = registry.execute(call(), invocation(false));
            assertThat(waiting.status()).isEqualTo(ToolResultStatus.APPROVAL_REQUIRED);
            assertThat(transport.callCalls.get()).isZero();

            ToolResult approved = registry.execute(call(), invocation(true));
            assertThat(approved.status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(approved.output().isArray()).isTrue();
            assertThat(transport.callCalls.get()).isEqualTo(1);
        }
    }

    private ToolCall call() {
        return new ToolCall("skill-mcp-call", "remote.echo", mapper.createObjectNode().put("text", "hello"));
    }

    private ToolInvocationContext invocation(boolean approvalGranted) {
        return new ToolInvocationContext(RUN_ID, "skill-test", "user-test", Path.of("."),
                Set.<RequiredCapability>of(), approvalGranted);
    }

    private final class RecordingTransport implements McpTransport {
        private final AtomicInteger callCalls = new AtomicInteger();

        @Override
        public McpJsonRpcResponse request(McpJsonRpcRequest request) {
            return switch (request.method()) {
                case "initialize" -> response(request.id(), """
                        {"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"test","version":"1"}}
                        """);
                case "tools/list" -> response(request.id(), """
                        {"tools":[{"name":"echo","description":"远程回显","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}}]}
                        """);
                case "tools/call" -> {
                    callCalls.incrementAndGet();
                    yield response(request.id(), """
                            {"content":[{"type":"text","text":"hello"}],"isError":false}
                            """);
                }
                default -> throw new AssertionError("未知 MCP 方法: " + request.method());
            };
        }

        @Override
        public void notify(McpJsonRpcRequest notification) {
            assertThat(notification.method()).isEqualTo("notifications/initialized");
        }

        private McpJsonRpcResponse response(String id, String json) {
            try {
                JsonNode result = mapper.readTree(json);
                return new McpJsonRpcResponse(id, Optional.of(result), Optional.empty());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
