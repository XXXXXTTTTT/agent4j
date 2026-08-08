package com.agent.web.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgePropertiesTest {

    @Test
    void validatesPositiveKnowledgeBudget() {
        KnowledgeProperties properties = new KnowledgeProperties(true, 4_000);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.maxTokens()).isEqualTo(4_000);
        properties.validate();
        assertThatThrownBy(() -> new KnowledgeProperties(true, 0).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-tokens");
    }
}
