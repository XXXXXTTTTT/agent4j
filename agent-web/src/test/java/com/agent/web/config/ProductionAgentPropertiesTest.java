package com.agent.web.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionAgentPropertiesTest {

    @Test
    void rejectsNonPositivePlannerContextTokenBudget() {
        assertThatThrownBy(() -> new ProductionAgentProperties(
                true,
                Path.of("."),
                "repo",
                "user",
                "",
                "DOCKER",
                "/bin/bash",
                "python:3.12-slim",
                "/workspace",
                "",
                "",
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                200,
                524_288,
                2,
                12,
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plannerContextMaxTokens");
    }
}
