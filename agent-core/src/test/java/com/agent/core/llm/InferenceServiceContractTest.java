package com.agent.core.llm;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 验证推理服务协议与端点能力契约。 */
class InferenceServiceContractTest {

    @Test
    void freezesCapabilitiesAndRejectsInvalidContract() {
        InferenceServiceContract contract = new InferenceServiceContract(
                "edge",
                "model-a",
                InferenceProtocol.OPENAI_CHAT_COMPLETIONS,
                EnumSet.of(
                        InferenceCapability.CHAT_COMPLETIONS,
                        InferenceCapability.STREAMING));

        assertThat(contract.capabilities())
                .containsExactlyInAnyOrder(
                        InferenceCapability.CHAT_COMPLETIONS,
                        InferenceCapability.STREAMING);
        assertThatThrownBy(() -> contract.capabilities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new InferenceServiceContract(
                "edge", "model-a", null, contract.capabilities()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("protocol");
        assertThatThrownBy(() -> new InferenceServiceContract(
                "edge", "model-a", InferenceProtocol.OPENAI_CHAT_COMPLETIONS, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capabilities");
    }

    @Test
    void legacyEndpointUsesOpenAiContractWithAllCapabilities() {
        ModelEndpoint endpoint = new ModelEndpoint(
                "legacy", "model-a", mock(LlmClient.class),
                CircuitBreaker.ofDefaults("legacy-contract"));

        assertThat(endpoint.serviceContract().protocol())
                .isEqualTo(InferenceProtocol.OPENAI_CHAT_COMPLETIONS);
        assertThat(endpoint.serviceContract().capabilities())
                .containsExactlyInAnyOrder(InferenceCapability.values());
    }
}
