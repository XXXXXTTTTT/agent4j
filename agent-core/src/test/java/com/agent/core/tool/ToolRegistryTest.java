package com.agent.core.tool;

import com.agent.core.intent.RequiredCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private static final UUID RUN_ID = UUID.fromString("70000000-0000-0000-0000-000000000020");
    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void registersFindsListsAndRejectsDuplicatesAndInvalidSchemas() {
        RecordingValidator validator = new RecordingValidator();
        try (DefaultToolRegistry registry = new DefaultToolRegistry(
                validator, new DefaultToolAuthorizer(), ToolAuditSink.noop(), new ObjectMapper(),
                new FixedClock())) {
            registry.register(definition("z.tool", ToolRiskLevel.LOW, Set.of(), (call, context) -> object("z", true)));
            registry.register(definition("a.tool", ToolRiskLevel.LOW, Set.of(), (call, context) -> object("a", true)));

            assertThat(registry.find("a.tool")).isPresent();
            assertThat(registry.list()).extracting(ToolDefinition::name)
                    .containsExactly("a.tool", "z.tool");
            assertThatThrownBy(() -> registry.list().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> registry.register(
                    definition("a.tool", ToolRiskLevel.LOW, Set.of(), (call, context) -> object("a", true))))
                    .isInstanceOf(ToolRegistrationException.class);

            validator.failSchema = true;
            assertThatThrownBy(() -> registry.register(
                    definition("invalid.tool", ToolRiskLevel.LOW, Set.of(), (call, context) -> object("x", true))))
                    .isInstanceOf(ToolRegistrationException.class)
                    .hasCauseInstanceOf(ToolSchemaException.class);
        }
    }

    @Test
    void executesInGovernedOrderAndAuditsWithoutArguments() {
        List<String> order = new ArrayList<>();
        List<ToolAuditEvent> events = new ArrayList<>();
        ToolSchemaValidator validator = new ToolSchemaValidator() {
            @Override
            public void validateSchema(com.fasterxml.jackson.databind.JsonNode schema) {
                order.add("validator-schema");
            }

            @Override
            public void validateArguments(com.fasterxml.jackson.databind.JsonNode schema,
                                           com.fasterxml.jackson.databind.JsonNode arguments) {
                order.add("validator-arguments");
            }
        };
        ToolAuthorizer authorizer = (definition, call, context) -> {
            order.add("authorizer");
            return new ToolAuthorization(ToolAuthorizationDecision.ALLOWED, "");
        };
        ToolHandler handler = (call, context) -> {
            order.add("handler");
            return object("ok", true);
        };
        try (DefaultToolRegistry registry = new DefaultToolRegistry(
                validator, authorizer, events::add, new ObjectMapper(), new FixedClock())) {
            registry.register(definition("code.run", ToolRiskLevel.LOW, Set.of(), handler));
            order.clear();
            ObjectNode arguments = JsonNodeFactory.instance.objectNode()
                    .put("secret", "do-not-audit")
                    .put("z", 1);
            ToolResult result = registry.execute(new ToolCall("call-1", "code.run", arguments), context());

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(result.output().path("ok").asBoolean()).isTrue();
            assertThat(order).containsExactly("validator-arguments", "authorizer", "handler");
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(events.getFirst().argumentsSha256()).isEqualTo(canonicalHash(arguments));
            assertThat(events.getFirst().toString()).doesNotContain("do-not-audit");
        }
    }

    @Test
    void preventsHandlerForSchemaAuthorizationAndApprovalFailures() {
        AtomicInteger calls = new AtomicInteger();
        List<ToolAuditEvent> events = new ArrayList<>();
        ToolSchemaValidator validator = new ToolSchemaValidator() {
            @Override
            public void validateSchema(com.fasterxml.jackson.databind.JsonNode schema) {
            }

            @Override
            public void validateArguments(com.fasterxml.jackson.databind.JsonNode schema,
                                           com.fasterxml.jackson.databind.JsonNode arguments) {
                throw new ToolSchemaException("/arguments", "参数不合法", null);
            }
        };
        try (DefaultToolRegistry registry = new DefaultToolRegistry(
                validator, new DefaultToolAuthorizer(), events::add, new ObjectMapper(), new FixedClock())) {
            registry.register(definition("safe.read", ToolRiskLevel.LOW, Set.of(), (call, context) -> {
                calls.incrementAndGet();
                return object("ok", true);
            }));
            ToolResult schemaDenied = registry.execute(
                    new ToolCall("call-schema", "safe.read", JsonNodeFactory.instance.objectNode()), context());
            assertThat(schemaDenied.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(schemaDenied.errorStack()).contains("ToolSchemaException");
            assertThat(calls).hasValue(0);

            ToolSchemaValidator pass = new JacksonToolSchemaValidator();
            try (DefaultToolRegistry deniedRegistry = new DefaultToolRegistry(
                    pass, new DefaultToolAuthorizer(), events::add, new ObjectMapper(), new FixedClock())) {
                deniedRegistry.register(definition("write.file", ToolRiskLevel.LOW,
                        Set.of(RequiredCapability.CODE_WRITE), (call, context) -> {
                            calls.incrementAndGet();
                            return object("ok", true);
                        }));
                ToolResult denied = deniedRegistry.execute(
                        new ToolCall("call-denied", "write.file", JsonNodeFactory.instance.objectNode()), context());
                assertThat(denied.status()).isEqualTo(ToolResultStatus.DENIED);
                assertThat(denied.errorStack()).contains("ToolAuthorizationException");

                deniedRegistry.register(definition("danger.exec", ToolRiskLevel.HIGH, Set.of(),
                        (call, context) -> {
                            calls.incrementAndGet();
                            return object("ok", true);
                        }));
                ToolResult approval = deniedRegistry.execute(
                        new ToolCall("call-approval", "danger.exec", JsonNodeFactory.instance.objectNode()), context());
                assertThat(approval.status()).isEqualTo(ToolResultStatus.APPROVAL_REQUIRED);
                assertThat(approval.errorStack()).contains("ToolApprovalRequiredException");
            }
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void convertsUnknownAndHandlerFailuresToCompleteStacks() {
        List<ToolAuditEvent> events = new ArrayList<>();
        try (DefaultToolRegistry registry = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), events::add,
                new ObjectMapper(), new FixedClock())) {
            ToolResult unknown = registry.execute(
                    new ToolCall("call-unknown", "missing.tool", JsonNodeFactory.instance.objectNode()), context());
            assertThat(unknown.status()).isEqualTo(ToolResultStatus.FAILED);
            assertThat(unknown.errorStack()).contains("ToolNotFoundException");
            assertThat(events.getFirst().riskLevel()).isEmpty();

            registry.register(definition("broken.tool", ToolRiskLevel.LOW, Set.of(), (call, context) -> {
                throw new IllegalStateException("handler boom");
            }));
            ToolResult failed = registry.execute(
                    new ToolCall("call-failed", "broken.tool", JsonNodeFactory.instance.objectNode()), context());
            assertThat(failed.status()).isEqualTo(ToolResultStatus.FAILED);
            assertThat(failed.errorStack()).contains("IllegalStateException", "handler boom");
            assertThat(events).hasSize(2);
        }
    }

    @Test
    void rejectsExecutionAfterClose() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.close();
        assertThatThrownBy(() -> registry.execute(
                new ToolCall("call-closed", "safe.read", JsonNodeFactory.instance.objectNode()), context()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register(
                definition("safe.read", ToolRiskLevel.LOW, Set.of(), (call, context) -> object("ok", true))))
                .isInstanceOf(IllegalStateException.class);
    }

    private ToolDefinition definition(String name, ToolRiskLevel risk, Set<RequiredCapability> capabilities,
                                      ToolHandler handler) {
        return new ToolDefinition(name, "测试工具", JsonNodeFactory.instance.objectNode().put("type", "object"),
                capabilities, risk, Duration.ofSeconds(1), handler);
    }

    private ToolInvocationContext context() {
        return context(Set.of());
    }

    private ToolInvocationContext context(Set<RequiredCapability> capabilities) {
        return new ToolInvocationContext(RUN_ID, "ops", "user-a", Path.of("."), capabilities, false);
    }

    private ObjectNode object(String field, boolean value) {
        return JsonNodeFactory.instance.objectNode().put(field, value);
    }

    private String canonicalHash(ObjectNode arguments) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(new ObjectMapper().writeValueAsBytes(arguments)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FixedClock implements LongSupplier {
        private long value;

        @Override
        public long getAsLong() {
            return value += 1_000_000;
        }
    }

    private static final class RecordingValidator implements ToolSchemaValidator {
        private boolean failSchema;

        @Override
        public void validateSchema(com.fasterxml.jackson.databind.JsonNode schema) {
            if (failSchema) {
                throw new ToolSchemaException("/type", "Schema 错误", null);
            }
        }

        @Override
        public void validateArguments(com.fasterxml.jackson.databind.JsonNode schema,
                                       com.fasterxml.jackson.databind.JsonNode arguments) {
        }
    }
}
