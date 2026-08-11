package com.agent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/** 模型端点熔断器的强类型配置。 */
@ConfigurationProperties(prefix = "agent.llm.circuit-breaker")
public record ModelCircuitBreakerProperties(
        Float failureRateThreshold,
        Integer minimumNumberOfCalls,
        Integer slidingWindowSize,
        Duration waitDurationInOpenState,
        Integer permittedNumberOfCallsInHalfOpenState) {

    /** 应用适配 Agent 低调用量的默认值并拒绝无效组合。 */
    @ConstructorBinding
    public ModelCircuitBreakerProperties {
        failureRateThreshold =
                failureRateThreshold == null ? 100.0f : failureRateThreshold;
        minimumNumberOfCalls =
                minimumNumberOfCalls == null ? 2 : minimumNumberOfCalls;
        slidingWindowSize =
                slidingWindowSize == null ? 2 : slidingWindowSize;
        waitDurationInOpenState = waitDurationInOpenState == null
                ? Duration.ofSeconds(30)
                : waitDurationInOpenState;
        permittedNumberOfCallsInHalfOpenState =
                permittedNumberOfCallsInHalfOpenState == null
                        ? 1
                        : permittedNumberOfCallsInHalfOpenState;

        if (failureRateThreshold < 0.0f || failureRateThreshold > 100.0f) {
            throw new IllegalArgumentException(
                    "agent.llm.circuit-breaker.failure-rate-threshold 必须在 0 到 100 之间");
        }
        if (minimumNumberOfCalls <= 0) {
            throw new IllegalArgumentException(
                    "agent.llm.circuit-breaker.minimum-number-of-calls 必须大于 0");
        }
        if (slidingWindowSize <= 0) {
            throw new IllegalArgumentException(
                    "agent.llm.circuit-breaker.sliding-window-size 必须大于 0");
        }
        if (minimumNumberOfCalls > slidingWindowSize) {
            throw new IllegalArgumentException(
                    "agent.llm.circuit-breaker.minimum-number-of-calls"
                            + " 不能大于 sliding-window-size");
        }
        if (waitDurationInOpenState.isZero()
                || waitDurationInOpenState.isNegative()) {
            throw new IllegalArgumentException(
                    "agent.llm.circuit-breaker.wait-duration-in-open-state 必须至少为 1ms");
        }
        if (permittedNumberOfCallsInHalfOpenState <= 0) {
            throw new IllegalArgumentException(
                    "agent.llm.circuit-breaker"
                            + ".permitted-number-of-calls-in-half-open-state 必须大于 0");
        }
    }
}
