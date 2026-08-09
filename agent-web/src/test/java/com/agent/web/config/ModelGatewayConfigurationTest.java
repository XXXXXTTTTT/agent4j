package com.agent.web.config;

import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证 Web 配置把端点能力和预算映射到 Core 契约。 */
class ModelGatewayConfigurationTest {

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
                .modelRouter(properties, mock(LlmClient.class));

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
