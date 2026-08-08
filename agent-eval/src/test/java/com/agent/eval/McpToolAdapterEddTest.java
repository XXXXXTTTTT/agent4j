package com.agent.eval;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolAuditEvent;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.core.tool.mcp.McpClient;
import com.agent.core.tool.mcp.McpJsonRpcRequest;
import com.agent.core.tool.mcp.McpJsonRpcResponse;
import com.agent.core.tool.mcp.McpRemoteToolException;
import com.agent.core.tool.mcp.McpToolRegistryAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** 对 MCP 工具适配层执行确定性 EDD。 */
@Tag("edd")
class McpToolAdapterEddTest {

    private static final UUID RUN_ID = UUID.fromString("72000000-0000-0000-0000-000000000001");
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "taskId", "status", "audited", "durationMs", "errorType", "passed");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluatesMcpGovernanceScenariosAndWritesStrictReport() throws Exception {
        List<EddResult> results = List.of(
                initialize(), discovery(), success(), schemaDenied(), capabilityDenied(),
                approvalDenied(), remoteFailure());
        Path report = Path.of("target", "edd", "mcp-tool-adapter-edd.json");
        Files.createDirectories(report.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of("scenarios", results));

        JsonNode json = mapper.readTree(report.toFile());
        assertThat(json.path("scenarios")).hasSize(7);
        for (JsonNode scenario : json.path("scenarios")) {
            List<String> fields = new ArrayList<>();
            scenario.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrderElementsOf(REQUIRED_FIELDS);
        }
        assertThat(results).allSatisfy(result -> {
            assertThat(result.passed()).as(result.taskId() + " EDD 失败").isTrue();
            assertThat(result.status()).isNotBlank();
            assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        });
        assertThat(results)
                .filteredOn(result -> Set.of("mcp.initialize", "mcp.discovery").contains(result.taskId()))
                .allSatisfy(result -> assertThat(result.audited()).isFalse());
        assertThat(results)
                .filteredOn(result -> !Set.of("mcp.initialize", "mcp.discovery").contains(result.taskId()))
                .allSatisfy(result -> assertThat(result.audited()).isTrue());
        assertThat(report).isRegularFile();
    }

    private EddResult initialize() {
        RecordingTransport transport = new RecordingTransport();
        return execute("mcp.initialize", transport, (registry, audits) -> {
            McpClient client = client(transport);
            client.initialize();
            boolean passed = transport.initializeCalls.get() == 1
                    && transport.notificationMethods.equals(List.of("notifications/initialized"));
            return new ScenarioObservation("INITIALIZED", passed, "", false);
        });
    }

    private EddResult discovery() {
        RecordingTransport transport = new RecordingTransport();
        return execute("mcp.discovery", transport, (registry, audits) -> {
            McpToolRegistryAdapter adapter = new McpToolRegistryAdapter(client(transport), registry);
            adapter.registerDiscoveredTools("remote", ToolRiskLevel.LOW, Set.of(), Duration.ofSeconds(1));
            boolean passed = registry.find("remote.echo").isPresent()
                    && transport.initializeCalls.get() == 1
                    && transport.listCalls.get() == 1;
            return new ScenarioObservation("DISCOVERED", passed, "", false);
        });
    }

    private EddResult success() {
        RecordingTransport transport = new RecordingTransport();
        return execute("mcp.success", transport, (registry, audits) -> {
            register(registry, transport, ToolRiskLevel.LOW, Set.of(), Duration.ofSeconds(1));
            ToolResult result = registry.execute(call(), context(Set.of(), false));
            boolean passed = result.status() == ToolResultStatus.SUCCEEDED
                    && result.output().isArray()
                    && result.output().path(0).path("text").textValue().equals("hello")
                    && transport.callCalls.get() == 1;
            return new ScenarioObservation(result.status().name(), passed, "", audits.size() == 1);
        });
    }

    private EddResult schemaDenied() {
        RecordingTransport transport = new RecordingTransport();
        return execute("mcp.schema-denied", transport, (registry, audits) -> {
            register(registry, transport, ToolRiskLevel.LOW, Set.of(), Duration.ofSeconds(1));
            ToolResult result = registry.execute(
                    new ToolCall("mcp.schema-denied", "remote.echo", mapper.createObjectNode()),
                    context(Set.of(), false));
            boolean passed = result.status() == ToolResultStatus.DENIED
                    && result.errorStack().contains("ToolSchemaException")
                    && transport.callCalls.get() == 0;
            return new ScenarioObservation(result.status().name(), passed, "ToolSchemaException", audits.size() == 1);
        });
    }

    private EddResult capabilityDenied() {
        RecordingTransport transport = new RecordingTransport();
        return execute("mcp.capability-denied", transport, (registry, audits) -> {
            register(registry, transport, ToolRiskLevel.LOW, Set.of(RequiredCapability.CODE_WRITE), Duration.ofSeconds(1));
            ToolResult result = registry.execute(call(), context(Set.of(), true));
            boolean passed = result.status() == ToolResultStatus.DENIED
                    && result.errorStack().contains("ToolAuthorizationException")
                    && transport.callCalls.get() == 0;
            return new ScenarioObservation(result.status().name(), passed, "ToolAuthorizationException", audits.size() == 1);
        });
    }

    private EddResult approvalDenied() {
        RecordingTransport transport = new RecordingTransport();
        return execute("mcp.approval-denied", transport, (registry, audits) -> {
            register(registry, transport, ToolRiskLevel.HIGH, Set.of(), Duration.ofSeconds(1));
            ToolResult result = registry.execute(call(), context(Set.of(), false));
            boolean passed = result.status() == ToolResultStatus.APPROVAL_REQUIRED
                    && result.errorStack().contains("ToolApprovalRequiredException")
                    && transport.callCalls.get() == 0;
            return new ScenarioObservation(result.status().name(), passed, "ToolApprovalRequiredException", audits.size() == 1);
        });
    }

    private EddResult remoteFailure() {
        RecordingTransport transport = new RecordingTransport();
        transport.callResult = "{\"content\":[{\"type\":\"text\",\"text\":\"remote boom\"}],\"isError\":true}";
        return execute("mcp.remote-failure", transport, (registry, audits) -> {
            register(registry, transport, ToolRiskLevel.LOW, Set.of(), Duration.ofSeconds(1));
            ToolResult result = registry.execute(call(), context(Set.of(), false));
            boolean passed = result.status() == ToolResultStatus.FAILED
                    && result.errorStack().contains(McpRemoteToolException.class.getSimpleName())
                    && result.errorStack().contains("remote boom")
                    && transport.callCalls.get() == 1;
            return new ScenarioObservation(result.status().name(), passed,
                    McpRemoteToolException.class.getSimpleName(), audits.size() == 1);
        });
    }

    private EddResult execute(
            String taskId,
            RecordingTransport transport,
            BiFunction<ToolRegistry, List<ToolAuditEvent>, ScenarioObservation> scenario) {
        long started = System.nanoTime();
        List<ToolAuditEvent> audits = new ArrayList<>();
        try (ToolRegistry registry = new DefaultToolRegistry(
                new com.agent.core.tool.JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), audits::add,
                mapper, System::nanoTime)) {
            ScenarioObservation observation = scenario.apply(registry, audits);
            String errorType = observation.errorType();
            return new EddResult(taskId, observation.status(), observation.audited(), elapsedMs(started),
                    errorType, observation.passed());
        } catch (Throwable exception) {
            return new EddResult(taskId, "EXCEPTION", false, elapsedMs(started),
                    exception.getClass().getSimpleName(), false);
        }
    }

    private void register(ToolRegistry registry, RecordingTransport transport, ToolRiskLevel risk,
                          Set<RequiredCapability> capabilities, Duration timeout) {
        new McpToolRegistryAdapter(client(transport), registry)
                .registerDiscoveredTools("remote", risk, capabilities, timeout);
    }

    private McpClient client(RecordingTransport transport) {
        return new McpClient(transport, mapper, "2025-06-18", "agent4j-edd", "0.1");
    }

    private ToolCall call() {
        return new ToolCall("mcp-call", "remote.echo", mapper.createObjectNode().put("text", "hello"));
    }

    private ToolInvocationContext context(Set<RequiredCapability> capabilities, boolean approvalGranted) {
        return new ToolInvocationContext(RUN_ID, "mcp-edd", "user-edd", Path.of("."), capabilities, approvalGranted);
    }

    private long elapsedMs(long started) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private McpJsonRpcResponse successResponse(String id, String json) {
        try {
            return new McpJsonRpcResponse(id, Optional.of(mapper.readTree(json)), Optional.empty());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record ScenarioObservation(String status, boolean passed, String errorType, boolean audited) {
    }

    private record EddResult(String taskId, String status, boolean audited, long durationMs,
                             String errorType, boolean passed) {
    }

    private final class RecordingTransport implements com.agent.core.tool.mcp.McpTransport {
        private final AtomicInteger initializeCalls = new AtomicInteger();
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicInteger callCalls = new AtomicInteger();
        private final List<String> notificationMethods = new ArrayList<>();
        private String callResult = "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"isError\":false}";

        @Override
        public McpJsonRpcResponse request(McpJsonRpcRequest request) {
            return switch (request.method()) {
                case "initialize" -> {
                    initializeCalls.incrementAndGet();
                    yield successResponse(request.id(), """
                            {"protocolVersion":"2025-06-18","capabilities":{},
                             "serverInfo":{"name":"edd","version":"1"}}
                            """);
                }
                case "tools/list" -> {
                    listCalls.incrementAndGet();
                    yield successResponse(request.id(), """
                            {"tools":[{"name":"echo","description":"Echo",
                            "inputSchema":{"type":"object","properties":{"text":{"type":"string"}},
                            "required":["text"],"additionalProperties":false}}]}
                            """);
                }
                case "tools/call" -> {
                    callCalls.incrementAndGet();
                    yield successResponse(request.id(), callResult);
                }
                default -> throw new AssertionError("未知 MCP 方法: " + request.method());
            };
        }

        @Override
        public void notify(McpJsonRpcRequest notification) {
            notificationMethods.add(notification.method());
        }
    }
}
