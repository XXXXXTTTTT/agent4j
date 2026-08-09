package com.agent.core.tool;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.security.DefaultOutputRedactor;
import com.agent.core.security.SecurityDecision;
import com.agent.core.security.SecurityViolation;
import com.agent.core.security.SecurityViolationSink;
import com.agent.core.security.ToolParameterDecision;
import com.agent.core.security.ToolParameterPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSecurityIntegrationTest {

    @Test
    void blocksParameterBeforeHandlerAndRecordsViolation() {
        AtomicInteger handlerCalls = new AtomicInteger();
        List<SecurityViolation> violations = new ArrayList<>();
        ToolParameterPolicy policy = (definition, call, context) ->
                new ToolParameterDecision(SecurityDecision.BLOCK,
                        "security.test-parameter", "参数被测试策略拒绝");
        try (DefaultToolRegistry registry = registry(policy, violations::add)) {
            registry.register(definition(handlerCalls));

            ToolResult result = registry.execute(
                    new ToolCall("call-1", "security.test", JsonNodeFactory.instance.objectNode()),
                    context());

            assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(handlerCalls).hasValue(0);
            assertThat(violations).extracting(SecurityViolation::type)
                    .containsExactly(com.agent.core.security.SecurityViolationType.TOOL_PARAMETER);
        }
    }

    @Test
    void redactsSuccessfulOutputBeforeReturningIt() {
        List<SecurityViolation> violations = new ArrayList<>();
        try (DefaultToolRegistry registry = registry(
                (definition, call, context) -> new ToolParameterDecision(
                        SecurityDecision.ALLOW, "", ""), violations::add)) {
            registry.register(new ToolDefinition(
                    "security.output", "测试输出", JsonNodeFactory.instance.objectNode()
                            .put("type", "object"), Set.of(), ToolRiskLevel.LOW,
                    Duration.ofSeconds(1), (call, context) -> new ObjectMapper().createObjectNode()
                            .put("token", "sk-test")
                            .put("ok", true)));

            ToolResult result = registry.execute(
                    new ToolCall("call-2", "security.output", JsonNodeFactory.instance.objectNode()),
                    context());

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCEEDED);
            assertThat(result.output().path("token").asText()).isEqualTo("[REDACTED]");
            assertThat(result.output().path("ok").asBoolean()).isTrue();
            assertThat(violations).isEmpty();
        }
    }

    private DefaultToolRegistry registry(ToolParameterPolicy policy, SecurityViolationSink sink) {
        return new DefaultToolRegistry(
                new JacksonToolSchemaValidator(), new DefaultToolAuthorizer(), ToolAuditSink.noop(),
                new ObjectMapper(), System::nanoTime, policy, new DefaultOutputRedactor(), sink);
    }

    private ToolDefinition definition(AtomicInteger handlerCalls) {
        return new ToolDefinition(
                "security.test", "测试工具", JsonNodeFactory.instance.objectNode()
                        .put("type", "object"), Set.of(RequiredCapability.CODE_READ),
                ToolRiskLevel.LOW, Duration.ofSeconds(1), (call, context) -> {
                    handlerCalls.incrementAndGet();
                    return JsonNodeFactory.instance.objectNode().put("ok", true);
                });
    }

    private ToolInvocationContext context() {
        return new ToolInvocationContext(
                UUID.randomUUID(), "planner", "user-1", Path.of("."),
                Set.of(RequiredCapability.CODE_READ), true);
    }
}
