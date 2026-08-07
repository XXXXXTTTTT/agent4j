package com.agent.web.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
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
                1_800_000,
                120_000,
                200_000,
                3,
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plannerContextMaxTokens");
    }

    @Test
    void mapsExactRuntimeLimitsToExecutionBudget() {
        ProductionAgentProperties properties = new ProductionAgentProperties(
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
                1_800_000,
                120_000,
                200_000,
                3,
                12_000);

        assertThat(properties.executionBudget().maxDuration())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.executionBudget().idleTimeout())
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.executionBudget().tokenBudget()).isEqualTo(200_000);
        assertThat(properties.executionBudget().maxSteps()).isEqualTo(12);
        assertThat(properties.executionBudget().noProgressLimit()).isEqualTo(3);
    }
}
