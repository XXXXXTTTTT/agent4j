package com.agent.core.security;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolInvocationContext;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolSecurityPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolDefinition definition = new ToolDefinition(
            "browser.navigate", "navigate", mapper.createObjectNode(), Set.of(),
            ToolRiskLevel.LOW, Duration.ofSeconds(1), (call, context) -> mapper.createObjectNode());
    private final ToolInvocationContext context = new ToolInvocationContext(
            UUID.randomUUID(), "planner", "user-1", java.nio.file.Path.of("."),
            Set.of(RequiredCapability.BROWSER), true);

    @Test
    void enforcesExactParameterPointersAndCredentialPatterns() throws Exception {
        DefaultToolParameterPolicy policy = new DefaultToolParameterPolicy(
                Map.of("browser.navigate", Set.of("/url")));

        assertThat(policy.inspect(definition, call("{\"url\":\"https://example.com\"}"), context).decision())
                .isEqualTo(SecurityDecision.ALLOW);
        assertThat(policy.inspect(definition, call("{\"selector\":\"#app\"}"), context).decision())
                .isEqualTo(SecurityDecision.BLOCK);
        assertThat(policy.inspect(definition, call("{\"url\":\"Bearer secret\"}"), context).decision())
                .isEqualTo(SecurityDecision.BLOCK);
    }

    @Test
    void redactsNestedSecretsWithoutChangingShape() throws Exception {
        OutputRedactor redactor = new DefaultOutputRedactor();
        JsonNode result = redactor.redact("browser.navigate", mapper.readTree(
                "{\"headers\":{\"authorization\":\"Bearer secret\"},\"items\":[{\"token\":\"sk-test\"}]}"));

        assertThat(result.at("/headers/authorization").asText()).isEqualTo("[REDACTED]");
        assertThat(result.at("/items/0/token").asText()).isEqualTo("[REDACTED]");
        assertThat(result.at("/items").isArray()).isTrue();
    }

    @Test
    void rejectsUnsanitizedSecurityViolationSummary() {
        assertThatThrownBy(() -> new SecurityViolation(
                UUID.randomUUID(), UUID.randomUUID(), "user-1", "planner", Optional.empty(),
                SecurityViolationType.PROMPT_INJECTION, SecuritySeverity.HIGH,
                "rule", "line\nvalue", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ToolCall call(String json) throws Exception {
        return new ToolCall("call-1", "browser.navigate", mapper.readTree(json));
    }
}
