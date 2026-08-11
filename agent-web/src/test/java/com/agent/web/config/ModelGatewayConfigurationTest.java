package com.agent.web.config;

import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证 Web 配置把端点能力和预算映射到 Core 契约。 */
class ModelGatewayConfigurationTest {

    @Test
    void createsCircuitBreakerConfigFromExactProperties() {
        ModelCircuitBreakerProperties properties =
                new ModelCircuitBreakerProperties(
                        73.0f,
                        4,
                        6,
                        Duration.ofSeconds(17),
                        2);

        CircuitBreakerConfig config =
                ModelGatewayConfiguration.circuitBreakerConfig(properties);

        assertThat(config.getFailureRateThreshold()).isEqualTo(73.0f);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(4);
        assertThat(config.getSlidingWindowSize()).isEqualTo(6);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
                .isEqualTo(Duration.ofSeconds(17).toMillis());
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState())
                .isEqualTo(2);
    }

    @Test
    void resolvesConfiguredV1PathOnlyOnceWhenBaseUrlAlreadyContainsV1() {
        ModelGatewayConfiguration.ResolvedEndpoint endpoint =
                ModelGatewayConfiguration.resolveEndpoint(
                        "https://zz.cxwms.com/v1", "/v1/chat/completions");

        assertThat(endpoint.transportBaseUrl()).isEqualTo("https://zz.cxwms.com");
        assertThat(endpoint.requestPath()).isEqualTo("/v1/chat/completions");
        assertThat(endpoint.requestUrl()).isEqualTo(
                "https://zz.cxwms.com/v1/chat/completions");
    }

    @Test
    void preservesConfiguredPathWhenBaseUrlHasNoPath() {
        ModelGatewayConfiguration.ResolvedEndpoint endpoint =
                ModelGatewayConfiguration.resolveEndpoint(
                        "https://api.example.com", "/v1/chat/completions");

        assertThat(endpoint.transportBaseUrl()).isEqualTo("https://api.example.com");
        assertThat(endpoint.requestPath()).isEqualTo("/v1/chat/completions");
        assertThat(endpoint.requestUrl()).isEqualTo(
                "https://api.example.com/v1/chat/completions");
    }

    @Test
    void createsExplicitContractsForEachTaskRoute() {
        ModelGatewayProperties properties = new ModelGatewayProperties(
                true,
                "https://api.example.com",
                "secret",
                "/v1/chat/completions",
                "code",
                "vision",
                "quick",
                "fallback",
                3,
                30,
                Duration.ofSeconds(1),
                Set.of(InferenceCapability.CHAT_COMPLETIONS, InferenceCapability.TOOL_CALLING),
                Set.of(InferenceCapability.CHAT_COMPLETIONS, InferenceCapability.VISION_INPUT),
                Set.of(InferenceCapability.CHAT_COMPLETIONS),
                Set.of(InferenceCapability.CHAT_COMPLETIONS));

        ModelRouter router = new ModelGatewayConfiguration()
                .modelRouter(
                        properties,
                        new ModelCircuitBreakerProperties(
                                null, null, null, null, null),
                        mock(LlmClient.class));

        assertThat(router.serviceContracts().get(TaskType.CODE).getFirst().capabilities())
                .containsExactlyInAnyOrder(
                        InferenceCapability.CHAT_COMPLETIONS,
                        InferenceCapability.TOOL_CALLING);
        assertThat(router.serviceContracts().get(TaskType.VISION).getFirst().capabilities())
                .containsExactlyInAnyOrder(
                        InferenceCapability.CHAT_COMPLETIONS,
                        InferenceCapability.VISION_INPUT);
        assertThat(router.serviceContracts().get(TaskType.CODE))
                .hasSize(2)
                .allMatch(contract -> contract.model().equals("code")
                        || contract.model().equals("fallback"));
    }
}
