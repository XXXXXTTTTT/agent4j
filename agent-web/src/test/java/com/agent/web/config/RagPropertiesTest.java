package com.agent.web.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagPropertiesTest {

    @Test
    void validatesEnabledRagEndpointAndIndexTimeout() {
        RagProperties properties = new RagProperties(
                true, "/v1/embeddings", "embed-test", true, true,
                false, Duration.ofMinutes(5));

        properties.validate();
        assertThat(properties.embeddingsPath()).isEqualTo("/v1/embeddings");
        assertThat(properties.indexTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThatThrownBy(() -> new RagProperties(
                true, "v1/embeddings", "embed-test", true, true,
                false, Duration.ofMinutes(5)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embeddings-path");
        assertThatThrownBy(() -> new RagProperties(
                true, "/v1/embeddings", "", true, true,
                false, Duration.ofMinutes(5)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-model");
        assertThatThrownBy(() -> new RagProperties(
                true, "/v1/embeddings", "embed-test", true, true,
                false, Duration.ZERO).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index-timeout");
    }
}
