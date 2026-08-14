package com.agent.web.command;

import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** EDD：显式开启后调用真实 OpenAI 协议网关，不替换为伪造成功响应。 */
class CommandEngineEddTest {

    @Test
    void realGatewayRespondsToWorkflowProbeWhenEnabled() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("AGENT_E2E_REAL_LLM")),
                "设置 AGENT_E2E_REAL_LLM=true 才执行真实 EDD");
        String baseUrl = required("AGENT_LLM_BASE_URL");
        String path = required("AGENT_LLM_CHAT_COMPLETIONS_PATH");
        String apiKey = required("AGENT_LLM_API_KEY");
        String model = required("AGENT_LLM_CODE_MODEL");
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        try (LlmClient client = new LlmClient(restClient, new ObjectMapper(), path)) {
            LlmClient.ChatCompletionResponse response = client.complete(
                    new LlmClient.ChatCompletionRequest(
                            model,
                            List.of(ChatMessage.user("Reply with exactly EDD_OK")),
                            List.of(), null, 0d, false));
            assertThat(response.choices()).isNotEmpty();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " 未配置");
        return value;
    }
}
