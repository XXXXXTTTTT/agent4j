package com.agent.web.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证模型端点熔断参数的默认值与边界。 */
class ModelCircuitBreakerPropertiesTest {

    @Test
    void usesAgentSizedDefaults() {
        ModelCircuitBreakerProperties properties =
                new ModelCircuitBreakerProperties(null, null, null, null, null);

        assertThat(properties.failureRateThreshold()).isEqualTo(100.0f);
        assertThat(properties.minimumNumberOfCalls()).isEqualTo(2);
        assertThat(properties.slidingWindowSize()).isEqualTo(2);
        assertThat(properties.waitDurationInOpenState())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.permittedNumberOfCallsInHalfOpenState())
                .isEqualTo(1);
    }

    @Test
    void rejectsFailureRateOutsideRange() {
        assertThatThrownBy(() -> new ModelCircuitBreakerProperties(
                -1.0f, 2, 2, Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure-rate-threshold");
        assertThatThrownBy(() -> new ModelCircuitBreakerProperties(
                100.1f, 2, 2, Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure-rate-threshold");
    }

    @Test
    void rejectsInvalidCountsAndDuration() {
        assertThatThrownBy(() -> new ModelCircuitBreakerProperties(
                100.0f, 0, 2, Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum-number-of-calls");
        assertThatThrownBy(() -> new ModelCircuitBreakerProperties(
                100.0f, 3, 2, Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum-number-of-calls");
        assertThatThrownBy(() -> new ModelCircuitBreakerProperties(
                100.0f, 2, 0, Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sliding-window-size");
        assertThatThrownBy(() -> new ModelCircuitBreakerProperties(
                100.0f, 2, 2, Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wait-duration-in-open-state");
        assertThatThrownBy(() -> new ModelCircuitBreakerProperties(
                100.0f, 2, 2, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wait-duration-in-open-state");
        assertThatThrownBy(() -> new ModelCircuitBreakerProperties(
                100.0f, 2, 2, Duration.ofSeconds(30), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permitted-number-of-calls-in-half-open-state");
    }
}
