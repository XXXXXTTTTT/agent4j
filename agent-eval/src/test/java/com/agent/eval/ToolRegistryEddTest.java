package com.agent.eval;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolAuditEvent;
import com.agent.core.tool.ToolAuditSink;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;
import com.agent.core.tool.ToolResultStatus;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.core.tool.ToolSchemaException;
import com.agent.core.tool.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 对统一工具治理协议执行确定性 EDD。 */
@Tag("edd")
class ToolRegistryEddTest {

    private static final UUID RUN_ID = UUID.fromString("70000000-0000-0000-0000-000000000030");
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "taskId", "status", "audited", "durationMs", "errorType", "passed");

    @Test
    void evaluatesGovernedToolScenariosAndWritesStrictReport() throws Exception {
        List<EddResult> results = List.of(
                success(), schemaDenied(), capabilityDenied(), approvalRequired(), timeout(), handlerFailure());
        Path report = Path.of("target", "edd", "tool-registry-edd.json");
        Files.createDirectories(report.getParent());
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of("scenarios", results));

        var json = mapper.readTree(report.toFile());
        assertThat(json.path("scenarios")).hasSize(6);
        for (var scenario : json.path("scenarios")) {
            List<String> fields = new ArrayList<>();
            scenario.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrderElementsOf(REQUIRED_FIELDS);
        }
        assertThat(results).allSatisfy(result -> {
            assertThat(result.passed()).as(result.taskId() + " EDD 失败").isTrue();
            assertThat(result.status()).isNotBlank();
            assertThat(result.audited()).isTrue();
            assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        });
        assertThat(report).isRegularFile();
    }

    private EddResult success() {
        return execute("tool.success", registry -> register(registry, "success.tool", ToolRiskLevel.LOW,
                Set.of(), (call, context) -> JsonNodeFactory.instance.objectNode().put("ok", true)),
                ToolResultStatus.SUCCEEDED, "");
    }

    private EddResult schemaDenied() {
        ToolSchemaValidator validator = new ToolSchemaValidator() {
            @Override
            public void validateSchema(com.fasterxml.jackson.databind.JsonNode schema) {
            }

            @Override
            public void validateArguments(com.fasterxml.jackson.databind.JsonNode schema,
                                           com.fasterxml.jackson.databind.JsonNode arguments) {
                throw new ToolSchemaException("/value", "参数被 Schema 拒绝", null);
            }
        };
        return execute("tool.schema-denied", registry -> {
            ToolRegistry custom = new DefaultToolRegistry(validator, new DefaultToolAuthorizer(),
                    registry.auditSink(), new ObjectMapper(), System::nanoTime);
            register(custom, "schema.tool", ToolRiskLevel.LOW, Set.of(),
                    (call, context) -> JsonNodeFactory.instance.objectNode());
            return custom;
        }, ToolResultStatus.DENIED, "ToolSchemaException");
    }

    private EddResult capabilityDenied() {
        return execute("tool.capability-denied", registry -> register(registry, "write.tool", ToolRiskLevel.LOW,
                Set.of(RequiredCapability.CODE_WRITE), (call, context) -> JsonNodeFactory.instance.objectNode()),
                ToolResultStatus.DENIED, "ToolAuthorizationException");
    }

    private EddResult approvalRequired() {
        return execute("tool.approval-required", registry -> register(registry, "danger.tool", ToolRiskLevel.HIGH,
                Set.of(), (call, context) -> JsonNodeFactory.instance.objectNode()),
                ToolResultStatus.APPROVAL_REQUIRED, "ToolApprovalRequiredException");
    }

    private EddResult timeout() {
        return execute("tool.timeout", registry -> register(registry, "timeout.tool", ToolRiskLevel.LOW,
                Set.of(), (call, context) -> {
                    try {
                        Thread.sleep(Duration.ofSeconds(2));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                    return JsonNodeFactory.instance.objectNode();
                }, Duration.ofMillis(20)), ToolResultStatus.TIMED_OUT, "ToolTimeoutException");
    }

    private EddResult handlerFailure() {
        return execute("tool.handler-failure", registry -> register(registry, "broken.tool", ToolRiskLevel.LOW,
                Set.of(), (call, context) -> {
                    throw new IllegalStateException("handler failed");
                }), ToolResultStatus.FAILED, "IllegalStateException");
    }

    private EddResult execute(String taskId, java.util.function.Function<EddRegistry, ToolRegistry> setup,
                              ToolResultStatus expectedStatus, String expectedErrorType) {
        List<ToolAuditEvent> audits = new CopyOnWriteArrayList<>();
        EddRegistry holder = new EddRegistry(audits);
        ToolRegistry registry = setup.apply(holder);
        try (registry) {
            ToolResult result = registry.execute(new ToolCall(taskId, toolName(taskId),
                            JsonNodeFactory.instance.objectNode().put("value", "edd")),
                    holder.context());
            Optional<ToolAuditEvent> audit = audits.stream().findFirst();
            boolean errorMatches = expectedErrorType.isEmpty()
                    ? result.errorStack().isEmpty()
                    : result.errorStack().contains(expectedErrorType);
            boolean passed = result.status() == expectedStatus
                    && audit.isPresent()
                    && audit.get().status() == expectedStatus
                    && errorMatches;
            return new EddResult(taskId, result.status().name(), audit.isPresent(), result.durationMs(),
                    audit.map(ToolAuditEvent::errorType).orElse(""), passed);
        } catch (Exception exception) {
            return new EddResult(taskId, "EXCEPTION", false, 0,
                    exception.getClass().getSimpleName(), false);
        }
    }

    private String toolName(String taskId) {
        return switch (taskId) {
            case "tool.success" -> "success.tool";
            case "tool.schema-denied" -> "schema.tool";
            case "tool.capability-denied" -> "write.tool";
            case "tool.approval-required" -> "danger.tool";
            case "tool.timeout" -> "timeout.tool";
            case "tool.handler-failure" -> "broken.tool";
            default -> throw new IllegalArgumentException("未定义 EDD taskId: " + taskId);
        };
    }

    private ToolRegistry register(EddRegistry registry, String name, ToolRiskLevel risk,
                                  Set<RequiredCapability> capabilities, com.agent.core.tool.ToolHandler handler) {
        return register(registry.registry(), name, risk, capabilities, handler, Duration.ofSeconds(1));
    }

    private ToolRegistry register(EddRegistry registry, String name, ToolRiskLevel risk,
                                  Set<RequiredCapability> capabilities, com.agent.core.tool.ToolHandler handler,
                                  Duration timeout) {
        return register(registry.registry(), name, risk, capabilities, handler, timeout);
    }

    private ToolRegistry register(ToolRegistry registry, String name, ToolRiskLevel risk,
                                  Set<RequiredCapability> capabilities, com.agent.core.tool.ToolHandler handler) {
        return register(registry, name, risk, capabilities, handler, Duration.ofSeconds(1));
    }

    private ToolRegistry register(ToolRegistry registry, String name, ToolRiskLevel risk,
                                  Set<RequiredCapability> capabilities, com.agent.core.tool.ToolHandler handler,
                                  Duration timeout) {
        registry.register(new ToolDefinition(name, "EDD 工具",
                JsonNodeFactory.instance.objectNode().put("type", "object"), capabilities,
                risk, timeout, handler));
        return registry;
    }

    private record EddRegistry(List<ToolAuditEvent> audits) {
        ToolRegistry registry() {
            return new DefaultToolRegistry(new com.agent.core.tool.JacksonToolSchemaValidator(),
                    new DefaultToolAuthorizer(), audits::add, new ObjectMapper(), System::nanoTime);
        }

        ToolAuditSink auditSink() {
            return audits::add;
        }

        com.agent.core.tool.ToolInvocationContext context() {
            return new com.agent.core.tool.ToolInvocationContext(RUN_ID, "edd", "user-edd", Path.of("."),
                    Set.of(), false);
        }
    }

    private record EddResult(String taskId, String status, boolean audited, long durationMs,
                             String errorType, boolean passed) {
    }
}
