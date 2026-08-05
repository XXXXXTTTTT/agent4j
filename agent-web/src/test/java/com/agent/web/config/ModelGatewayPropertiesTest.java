package com.agent.web.config;

import org.junit.jupiter.api.Test;

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
    }
}
