package com.agent.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ImageGenerationClientTest {

    @Test
    void postsImageGenerationRequestAndNormalizesBase64Output() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://image.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://image.test/v1/images/generations"))
                .andExpect(content().string(containsString("\"model\":\"gpt-image-2\"")))
                .andExpect(content().string(containsString("\"prompt\":\"蓝色方块\"")))
                .andRespond(withSuccess("""
                        {"created":1,"data":[{"b64_json":"AA==","revised_prompt":"蓝色方块"}]}
                        """, MediaType.APPLICATION_JSON));

        try (ImageGenerationClient client = new ImageGenerationClient(
                builder.build(), mapper, "/v1/images/generations", "gpt-image-2")) {
            ImageGenerationClient.GeneratedImage image = client.generate("蓝色方块");
            assertThat(image.dataUrl()).isEqualTo("data:image/png;base64,AA==");
            assertThat(image.revisedPrompt()).isEqualTo("蓝色方块");
        }
        server.verify();
    }
}
