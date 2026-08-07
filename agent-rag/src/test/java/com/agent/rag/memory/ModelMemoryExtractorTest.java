package com.agent.rag.memory;

import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelMemoryExtractorTest {

    private static final String PATH = "/v1/chat/completions";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<LlmClient> clients = new ArrayList<>();
    private final List<MockRestServiceServer> servers = new ArrayList<>();

    @AfterEach
    void closeClients() {
        clients.forEach(LlmClient::close);
        servers.forEach(MockRestServiceServer::verify);
    }

    @Test
    void parsesExactMemoryJsonAndRoutesQuickClassification() {
        Endpoint endpoint = endpoint();
        endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model": "quick-model",
                          "messages": [
                            {"role":"system"},
                            {"role":"user","content":"The user prefers constructors."}
                          ],
                          "tools": [],
                          "temperature": 0.0,
                          "stream": false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {
                          "id":"memory-response","object":"chat.completion","created":1,
                          "model":"quick-model","choices":[{"index":0,
                          "message":{"role":"assistant","content":"{\\"memories\\":[{\\"type\\":\\"USER_PREFERENCE\\",\\"title\\":\\"Constructors\\",\\"content\\":\\"Use constructor injection.\\",\\"importance\\":0.8},{\\"type\\":\\"BAD_CASE\\",\\"title\\":\\"Timeout\\",\\"content\\":\\"PTY timeout required cleanup.\\",\\"importance\\":1.0}]}"},
                          "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        List<MemoryDraft> drafts = new ModelMemoryExtractor(router(endpoint), objectMapper)
                .extract(new MemoryCapture("repo", "user", "The user prefers constructors."));

        assertThat(drafts).extracting(MemoryDraft::type)
                .containsExactly(MemoryType.USER_PREFERENCE, MemoryType.BAD_CASE);
        assertThat(drafts.getFirst().title()).isEqualTo("Constructors");
        assertThat(drafts.getFirst().content()).isEqualTo("Use constructor injection.");
        assertThat(drafts).extracting(MemoryDraft::importance)
                .containsExactly(0.8, 1.0);
    }

    @Test
    void rejectsUnknownFieldsAndInvalidItemsWithOriginalCause() {
        for (String response : List.of(
                "{\"memories\":[{\"type\":\"USER_PREFERENCE\",\"title\":\"t\",\"content\":\"c\",\"importance\":0.5,\"extra\":true}]}",
                "{\"memories\":[{\"type\":\"UNKNOWN\",\"title\":\"t\",\"content\":\"c\",\"importance\":0.5}]}",
                "{\"memories\":[{\"type\":\"USER_PREFERENCE\",\"title\":null,\"content\":\"c\",\"importance\":0.5}]}",
                "{\"memories\":[{\"type\":\"USER_PREFERENCE\",\"title\":\"t\",\"content\":\"c\"}]}",
                "{\"memories\":[{\"type\":\"USER_PREFERENCE\",\"title\":\"t\",\"content\":\"c\",\"importance\":1.1}]}",
                "{\"unknown\":[]}")
        ) {
            Endpoint endpoint = endpoint();
            endpoint.server().expect(once(), requestTo(endpoint.baseUrl() + PATH))
                    .andRespond(withSuccess(responseJson(endpoint, response), MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> new ModelMemoryExtractor(router(endpoint), objectMapper)
                    .extract(new MemoryCapture("repo", "user", "source")))
                    .isInstanceOf(MemoryExtractionException.class)
                    .satisfies(exception -> assertThat(exception.getCause()).isNotNull());
        }
    }

    private String responseJson(Endpoint endpoint, String content) {
        return """
                {"id":"memory-response","object":"chat.completion","created":1,
                 "model":"%s","choices":[{"index":0,
                 "message":{"role":"assistant","content":%s},"finish_reason":"stop"}]}
                """.formatted(endpoint.model(), objectMapper.valueToTree(content));
    }

    private ModelRouter router(Endpoint endpoint) {
        Map<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) {
            routes.put(type, List.of(endpoint.modelEndpoint()));
        }
        return new ModelRouter(routes);
    }

    private Endpoint endpoint() {
        String baseUrl = "https://memory-extractor.test/" + clients.size();
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(builder.build(), objectMapper, PATH);
        clients.add(client);
        servers.add(server);
        ModelEndpoint modelEndpoint = new ModelEndpoint(
                "memory-extractor", "quick-model", client,
                CircuitBreaker.ofDefaults("memory-extractor-" + clients.size()));
        return new Endpoint(modelEndpoint, baseUrl, server);
    }

    private record Endpoint(ModelEndpoint modelEndpoint, String baseUrl, MockRestServiceServer server) {
        private String model() {
            return modelEndpoint.model();
        }
    }
}
