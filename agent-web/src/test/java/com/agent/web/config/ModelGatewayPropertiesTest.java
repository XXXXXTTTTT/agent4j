package com.agent.web.config;

import com.agent.core.llm.InferenceCapability;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 .env 对模型网关配置的强类型校验。 */
class ModelGatewayPropertiesTest {

    @Test
    void disabledGatewayDoesNotRequireCredentials() {
        ModelGatewayProperties properties = new ModelGatewayProperties(
                false, "", "", "/v1/chat/completions", "", "", "", "");

        properties.validate();

        assertThat(properties.enabled()).isFalse();
    }

    @Test
    void enabledGatewayRequiresEndpointAndAllModels() {
        ModelGatewayProperties properties = new ModelGatewayProperties(
                true, "https://api.example.com", "secret", "/v1/chat/completions",
                "code", "vision", "quick", "");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallback-model");
    }

    @Test
    void completeGatewayConfigurationPassesValidation() {
        ModelGatewayProperties properties = new ModelGatewayProperties(
                true, "https://api.example.com", "secret", "/v1/chat/completions",
                "code", "vision", "quick", "fallback");

        properties.validate();

        assertThat(properties.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(properties.maxConcurrentRequests()).isEqualTo(8);
        assertThat(properties.maxRequestsPerMinute()).isEqualTo(120);
        assertThat(properties.queueTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.codeCapabilities())
                .containsExactlyInAnyOrder(
                        InferenceCapability.CHAT_COMPLETIONS,
                        InferenceCapability.STREAMING,
                        InferenceCapability.TOOL_CALLING);
    }

    @Test
    void rejectsInvalidInferenceBudget() {
        assertThatThrownBy(() -> new ModelGatewayProperties(
                true, "https://api.example.com", "secret", "/v1/chat/completions",
                "code", "vision", "quick", "fallback",
                0, 120, Duration.ofSeconds(2),
                Set.of(InferenceCapability.CHAT_COMPLETIONS),
                Set.of(InferenceCapability.CHAT_COMPLETIONS),
                Set.of(InferenceCapability.CHAT_COMPLETIONS),
                Set.of(InferenceCapability.CHAT_COMPLETIONS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-concurrent-requests");

        assertThatThrownBy(() -> new ModelGatewayProperties(
                true, "https://api.example.com", "secret", "/v1/chat/completions",
                "code", "vision", "quick", "fallback",
                8, 120, Duration.ofSeconds(2),
                Set.of(),
                Set.of(InferenceCapability.CHAT_COMPLETIONS),
                Set.of(InferenceCapability.CHAT_COMPLETIONS),
                Set.of(InferenceCapability.CHAT_COMPLETIONS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code-capabilities");
    }
}
