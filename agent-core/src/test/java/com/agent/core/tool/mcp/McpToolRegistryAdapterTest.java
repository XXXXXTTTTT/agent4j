package com.agent.core.tool.mcp;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.core.tool.ToolSchemaException;
import com.agent.core.tool.ToolSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolRegistryAdapterTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsNamespaceAndRemoteNameWithoutChangingEitherValue() {
        RecordingTransport transport = initializedTransport();
        transport.toolList = """
                {"tools":[{"name":"echo-tool.v1","description":"Echo",
                "inputSchema":{"type":"object","properties":{"text":{"type":"string"}},
                "required":["text"],"additionalProperties":false}}]}
                """;
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            McpToolRegistryAdapter adapter = new McpToolRegistryAdapter(client(transport), registry);

            adapter.registerDiscoveredTools("remote.ns", ToolRiskLevel.LOW, Set.of(), Duration.ofSeconds(1));

            assertThat(registry.find("remote.ns.echo-tool.v1")).isPresent();
            assertThat(registry.find("remote.ns.ECHO-TOOL.V1")).isEmpty();
            assertThat(transport.initializeCalls).hasValue(1);
            assertThat(transport.listCalls).hasValue(1);
        }
    }

    @Test
    void rejectsInvalidNamespaceOrCombinedNameBeforeRegistration() {
        RecordingTransport transport = initializedTransport();
        transport.toolList = """
                {"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object"}}]}
                """;
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            McpToolRegistryAdapter adapter = new McpToolRegistryAdapter(client(transport), registry);

            assertThatThrownBy(() -> adapter.registerDiscoveredTools("Bad Namespace", ToolRiskLevel.LOW,
                    Set.of(), Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adapter.registerDiscoveredTools("valid.ns.", ToolRiskLevel.LOW,
                    Set.of(), Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(registry.list()).isEmpty();
        }
    }

    @Test
    void executesRemoteHandlerOnceAndReturnsContentArray() {
        RecordingTransport transport = initializedTransport();
        transport.toolList = """
                {"tools":[{"name":"echo","description":"Echo",
                "inputSchema":{"type":"object","properties":{"text":{"type":"string"}},
                "required":["text"],"additionalProperties":false}}]}
                """;
        transport.callResult = """
                {"content":[{"type":"text","text":"hello"}],"isError":false}
                """;
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            McpToolRegistryAdapter adapter = new McpToolRegistryAdapter(client(transport), registry);
            adapter.registerDiscoveredTools("remote", ToolRiskLevel.LOW, Set.of(), Duration.ofSeconds(1));

            ToolResult result = registry.execute(
                    new ToolCall("call-1", "remote.echo", objectMapper.createObjectNode().put("text", "hello")),
                    context(Set.of(), false));

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(result.output().isArray()).isTrue();
            assertThat(result.output().get(0).get("text").textValue()).isEqualTo("hello");
            assertThat(transport.callCalls).hasValue(1);
            assertThat(transport.lastRemoteName).isEqualTo("echo");
        }
    }

    @Test
    void registryGovernancePreventsRemoteCallForSchemaCapabilityAndApproval() {
        RecordingTransport transport = initializedTransport();
        transport.toolList = """
                {"tools":[{"name":"write","description":"Write",
                "inputSchema":{"type":"object","properties":{"text":{"type":"string"}},
                "required":["text"],"additionalProperties":false}}]}
                """;
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            McpToolRegistryAdapter adapter = new McpToolRegistryAdapter(client(transport), registry);
            adapter.registerDiscoveredTools("remote", ToolRiskLevel.HIGH, Set.of(RequiredCapability.CODE_WRITE),
                    Duration.ofSeconds(1));

            ToolResult schemaDenied = registry.execute(
                    new ToolCall("call-schema", "remote.write", objectMapper.createObjectNode()),
                    context(Set.of(RequiredCapability.CODE_WRITE), true));
            ToolResult capabilityDenied = registry.execute(
                    new ToolCall("call-capability", "remote.write",
                            objectMapper.createObjectNode().put("text", "x")), context(Set.of(), true));
            ToolResult approvalRequired = registry.execute(
                    new ToolCall("call-approval", "remote.write",
                            objectMapper.createObjectNode().put("text", "x")), context(Set.of(RequiredCapability.CODE_WRITE), false));

            assertThat(schemaDenied.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(capabilityDenied.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(approvalRequired.status()).isEqualTo(ToolResultStatus.APPROVAL_REQUIRED);
            assertThat(transport.callCalls).hasValue(0);
        }
    }

    @Test
    void convertsRemoteErrorToFailedResultWithContentAndExceptionType() {
        RecordingTransport transport = initializedTransport();
        transport.toolList = """
                {"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object"}}]}
                """;
        transport.callResult = """
                {"content":[{"type":"text","text":"remote boom"}],"isError":true}
                """;
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            McpToolRegistryAdapter adapter = new McpToolRegistryAdapter(client(transport), registry);
            adapter.registerDiscoveredTools("remote", ToolRiskLevel.LOW, Set.of(), Duration.ofSeconds(1));

            ToolResult result = registry.execute(
                    new ToolCall("call-error", "remote.echo", objectMapper.createObjectNode()), context(Set.of(), false));

            assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
            assertThat(result.errorStack()).contains("McpRemoteToolException", "remote boom");
            assertThat(transport.callCalls).hasValue(1);
        }
    }

    @Test
    void registryTimeoutCancelsRemoteHandlerWithBoundedResult() {
        RecordingTransport transport = initializedTransport();
        transport.toolList = "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}]}";
        transport.callDelayMs = 250;
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            McpToolRegistryAdapter adapter = new McpToolRegistryAdapter(client(transport), registry);
            adapter.registerDiscoveredTools("remote", ToolRiskLevel.LOW, Set.of(), Duration.ofMillis(20));

            ToolResult result = registry.execute(
                    new ToolCall("call-timeout", "remote.echo", objectMapper.createObjectNode()),
                    context(Set.of(), false));

            assertThat(result.status()).isEqualTo(ToolResultStatus.TIMED_OUT);
            assertThat(transport.callCalls).hasValue(1);
        }
    }

    @Test
    void rejectsDuplicateLocalRegistrationAtomically() {
        RecordingTransport first = initializedTransport();
        first.toolList = "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}]}";
        RecordingTransport second = initializedTransport();
        second.toolList = first.toolList;
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            new McpToolRegistryAdapter(client(first), registry)
                    .registerDiscoveredTools("remote", ToolRiskLevel.LOW, Set.of(), Duration.ofSeconds(1));

            assertThatThrownBy(() -> new McpToolRegistryAdapter(client(second), registry)
                    .registerDiscoveredTools("remote", ToolRiskLevel.LOW, Set.of(), Duration.ofSeconds(1)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(registry.list()).extracting(definition -> definition.name())
                    .containsExactly("remote.echo");
        }
    }

    @Test
    void rejectsUntrustedSchemaBeforeRegisteringAnyDiscoveredTool() {
        RecordingTransport transport = initializedTransport();
        transport.toolList = """
                {"tools":[
                  {"name":"valid","description":"Valid","inputSchema":{"type":"object"}},
                  {"name":"invalid","description":"Invalid","inputSchema":{"type":"object","$ref":"unsafe"}}
                ]}
                """;
        try (DefaultToolRegistry registry = new DefaultToolRegistry()) {
            McpToolRegistryAdapter adapter = new McpToolRegistryAdapter(client(transport), registry);

            assertThatThrownBy(() -> adapter.registerDiscoveredTools("remote", ToolRiskLevel.LOW,
                    Set.of(), Duration.ofSeconds(1)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Schema");
            assertThat(registry.list()).isEmpty();
        }
    }

    @Test
    void customRegistrySchemaFailureDoesNotLeavePartiallyRegisteredTools() {
        RecordingTransport transport = initializedTransport();
        transport.toolList = """
                {"tools":[
                  {"name":"first","description":"First","inputSchema":{"type":"object"}},
                  {"name":"second","description":"Second",
                   "inputSchema":{"type":"object","title":"reject-by-registry"}}
                ]}
                """;
        ToolSchemaValidator validator = new ToolSchemaValidator() {
            @Override
            public void validateSchema(JsonNode schema) {
                if ("reject-by-registry".equals(schema.path("title").textValue())) {
                    throw new ToolSchemaException("/title", "自定义 Registry 拒绝 Schema", null);
                }
            }

            @Override
            public void validateArguments(JsonNode schema, JsonNode arguments) {
            }
        };
        try (DefaultToolRegistry registry = new DefaultToolRegistry(
                validator, new DefaultToolAuthorizer(), event -> { }, objectMapper, System::nanoTime)) {
            McpToolRegistryAdapter adapter = new McpToolRegistryAdapter(client(transport), registry);

            assertThatThrownBy(() -> adapter.registerDiscoveredTools("remote", ToolRiskLevel.LOW,
                    Set.of(), Duration.ofSeconds(1)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Schema");
            assertThat(registry.list()).isEmpty();
        }
    }

    private McpClient client(RecordingTransport transport) {
        return new McpClient(transport, objectMapper, "2025-06-18", "agent4j", "0.1");
    }

    private RecordingTransport initializedTransport() {
        RecordingTransport transport = new RecordingTransport();
        transport.responses.add(success("1", """
                {"protocolVersion":"2025-06-18","capabilities":{},
                 "serverInfo":{"name":"demo","version":"1"}}
                """));
        return transport;
    }

    private McpJsonRpcResponse success(String id, String result) {
        try {
            return new McpJsonRpcResponse(id, Optional.of(objectMapper.readTree(result)), Optional.empty());
        } catch (Exception exception) {
            throw new AssertionError("测试响应 JSON 无效", exception);
        }
    }

    private ToolInvocationContext context(Set<RequiredCapability> capabilities, boolean approvalGranted) {
        return new ToolInvocationContext(RUN_ID, "mcp", "user-a", Path.of("."), capabilities, approvalGranted);
    }

    private static final class RecordingTransport implements McpTransport {
        private final Deque<McpJsonRpcResponse> responses = new ArrayDeque<>();
        private final AtomicInteger initializeCalls = new AtomicInteger();
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicInteger callCalls = new AtomicInteger();
        private String toolList = "{\"tools\":[]}";
        private String callResult = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}";
        private String lastRemoteName;
        private long callDelayMs;

        @Override
        public McpJsonRpcResponse request(McpJsonRpcRequest request) {
            return switch (request.method()) {
                case "initialize" -> {
                    initializeCalls.incrementAndGet();
                    yield responses.removeFirst();
                }
                case "tools/list" -> {
                    listCalls.incrementAndGet();
                    yield successResponse(request.id(), toolList);
                }
                case "tools/call" -> {
                    callCalls.incrementAndGet();
                    lastRemoteName = request.params().path("name").textValue();
                    if (callDelayMs > 0) {
                        try {
                            Thread.sleep(callDelayMs);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    yield successResponse(request.id(), callResult);
                }
                default -> throw new AssertionError("未知 MCP 方法: " + request.method());
            };
        }

        @Override
        public void notify(McpJsonRpcRequest notification) {
        }

        private McpJsonRpcResponse successResponse(String id, String json) {
            try {
                return new McpJsonRpcResponse(id, Optional.of(new ObjectMapper().readTree(json)), Optional.empty());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
