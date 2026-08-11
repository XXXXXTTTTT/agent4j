package com.agent.core.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 OpenAI 兼容端点的基础地址与请求路径只组合一次。 */
class OpenAiEndpointTest {

    @Test
    void resolvesVersionPathOnlyOnceWhenBaseUrlAlreadyContainsIt() {
        OpenAiEndpoint endpoint = OpenAiEndpoint.resolve(
                "https://zz.cxwms.com/v1", "/v1/chat/completions");

        assertThat(endpoint.transportBaseUrl()).isEqualTo("https://zz.cxwms.com");
        assertThat(endpoint.requestPath()).isEqualTo("/v1/chat/completions");
        assertThat(endpoint.requestUrl()).isEqualTo(
                "https://zz.cxwms.com/v1/chat/completions");
    }

    @Test
    void preservesConfiguredPathWhenBaseUrlHasNoPath() {
        OpenAiEndpoint endpoint = OpenAiEndpoint.resolve(
                "https://api.example.com", "/v1/chat/completions");

        assertThat(endpoint.transportBaseUrl()).isEqualTo("https://api.example.com");
        assertThat(endpoint.requestPath()).isEqualTo("/v1/chat/completions");
        assertThat(endpoint.requestUrl()).isEqualTo(
                "https://api.example.com/v1/chat/completions");
    }
}
