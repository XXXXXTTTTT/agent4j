package com.agent.rag.pipeline;

import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.prompt.PromptCatalog;
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

class ModelRetrievalEnhancerTest {

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
    void registersExactPromptNamesAndVersions() {
        PromptCatalog catalog = RagPromptTemplates.catalog();

        assertThat(catalog.render(
                "rag.rewrite", "1",
                Map.of("query", "original", "limit", "2")))
                .satisfies(prompt -> {
                    assertThat(prompt.name()).isEqualTo("rag.rewrite");
                    assertThat(prompt.version()).isEqualTo("1");
                    assertThat(prompt.dynamicSection())
                            .contains("original")
                            .contains("2");
                });
        assertThat(catalog.render(
                "rag.hyde", "1", Map.of("query", "original")))
                .satisfies(prompt -> {
                    assertThat(prompt.name()).isEqualTo("rag.hyde");
                    assertThat(prompt.version()).isEqualTo("1");
                    assertThat(prompt.dynamicSection()).contains("original");
                });
    }

    @Test
    void rewritesWithQuickClassificationAndZeroTemperature() {
        RouterHarness harness = routerHarness();
        harness.quick().server().expect(
                        once(), requestTo(harness.quick().baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"quick-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"原始查询:\noriginal query\n最多返回 2 条额外查询。"}
                          ],
                          "tools":[],
                          "temperature":0.0,
                          "stream":false
                        }
                        """, false))
                .andRespond(withSuccess(
                        textResponse("[\"rewrite one\",\"rewrite two\"]"),
                        MediaType.APPLICATION_JSON));

        List<String> rewritten = new ModelQueryRewriter(
                harness.router(), objectMapper).rewrite("original query", 2);

        assertThat(rewritten).containsExactly("rewrite one", "rewrite two");
    }

    @Test
    void rejectsNonArrayFencedEmptyExcessAndNonTextRewriteResponses() {
        for (String response : List.of(
                "{\"query\":\"rewrite\"}",
                "```json\n[\"rewrite\"]\n```",
                "[\"rewrite\",\" \"]",
                "[\"one\",\"two\"]",
                "[\"rewrite\"] trailing",
                "[\"rewrite\"][\"second\"]")) {
            RouterHarness harness = routerHarness();
            harness.quick().server().expect(
                            once(), requestTo(harness.quick().baseUrl() + PATH))
                    .andRespond(withSuccess(
                            textResponse(response), MediaType.APPLICATION_JSON));
            int limit = response.equals("[\"one\",\"two\"]") ? 1 : 2;

            assertThatThrownBy(() -> new ModelQueryRewriter(
                    harness.router(), objectMapper).rewrite("query", limit))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("endpoint=quick-endpoint")
                    .hasMessageContaining("model=quick-model")
                    .satisfies(exception -> assertThat(exception.getCause()).isNotNull());
        }

        RouterHarness harness = routerHarness();
        harness.quick().server().expect(
                        once(), requestTo(harness.quick().baseUrl() + PATH))
                .andRespond(withSuccess(toolResponse(), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new ModelQueryRewriter(
                harness.router(), objectMapper).rewrite("query", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint=quick-endpoint")
                .hasMessageContaining("model=quick-model")
                .hasRootCauseMessage("查询改写模型响应 content 必须是 TextContent");
    }

    @Test
    void generatesHydeTextWithQuickClassificationAndRejectsToolContent() {
        RouterHarness success = routerHarness();
        success.quick().server().expect(
                        once(), requestTo(success.quick().baseUrl() + PATH))
                .andExpect(content().json("""
                        {
                          "model":"quick-model",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"原始查询:\nexplain retrieval"}
                          ],
                          "tools":[],
                          "temperature":0.0,
                          "stream":false
                        }
                        """, false))
                .andRespond(withSuccess(
                        textResponse("A retrieval implementation uses RRF."),
                        MediaType.APPLICATION_JSON));

        String document = new ModelHypotheticalDocumentGenerator(
                success.router(), objectMapper).generate("explain retrieval");

        assertThat(document).isEqualTo("A retrieval implementation uses RRF.");

        RouterHarness tool = routerHarness();
        tool.quick().server().expect(
                        once(), requestTo(tool.quick().baseUrl() + PATH))
                .andRespond(withSuccess(toolResponse(), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new ModelHypotheticalDocumentGenerator(
                tool.router(), objectMapper).generate("query"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint=quick-endpoint")
                .hasMessageContaining("model=quick-model")
                .hasRootCauseMessage("HyDE 模型响应 content 必须是 TextContent");
    }

    @Test
    void rejectsBlankHydeText() {
        RouterHarness harness = routerHarness();
        harness.quick().server().expect(
                        once(), requestTo(harness.quick().baseUrl() + PATH))
                .andRespond(withSuccess(
                        textResponse("   "), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new ModelHypotheticalDocumentGenerator(
                harness.router(), objectMapper).generate("query"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint=quick-endpoint")
                .hasMessageContaining("model=quick-model")
                .hasRootCauseMessage("HyDE 模型响应不能为空");
    }

    private RouterHarness routerHarness() {
        EnumMap<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        Endpoint quick = null;
        for (TaskType type : TaskType.values()) {
            Endpoint endpoint = endpoint(type);
            routes.put(type, List.of(endpoint.modelEndpoint()));
            if (type == TaskType.QUICK_CLASSIFICATION) {
                quick = endpoint;
            }
        }
        return new RouterHarness(new ModelRouter(routes), quick);
    }

    private Endpoint endpoint(TaskType taskType) {
        String label = taskType.name().toLowerCase(java.util.Locale.ROOT);
        String baseUrl = "https://rag-enhancer.test/" + clients.size() + "/" + label;
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(builder.build(), objectMapper, PATH);
        clients.add(client);
        servers.add(server);
        String endpointName = taskType == TaskType.QUICK_CLASSIFICATION
                ? "quick-endpoint" : label + "-endpoint";
        String model = taskType == TaskType.QUICK_CLASSIFICATION
                ? "quick-model" : label + "-model";
        ModelEndpoint modelEndpoint = new ModelEndpoint(
                endpointName,
                model,
                client,
                CircuitBreaker.ofDefaults(endpointName + "-" + clients.size()));
        return new Endpoint(modelEndpoint, baseUrl, server);
    }

    private String textResponse(String responseContent) {
        return """
                {"id":"rag-response","object":"chat.completion","created":1,
                 "model":"quick-model","choices":[{"index":0,
                 "message":{"role":"assistant","content":%s},"finish_reason":"stop"}]}
                """.formatted(objectMapper.valueToTree(responseContent));
    }

    private String toolResponse() {
        return """
                {"id":"rag-response","object":"chat.completion","created":1,
                 "model":"quick-model","choices":[{"index":0,
                 "message":{"role":"assistant","content":null,"tool_calls":[{
                   "id":"call-1","type":"function","function":{"name":"tool","arguments":"{}"}
                 }]},"finish_reason":"tool_calls"}]}
                """;
    }

    private record RouterHarness(ModelRouter router, Endpoint quick) {
    }

    private record Endpoint(
            ModelEndpoint modelEndpoint,
            String baseUrl,
            MockRestServiceServer server) {
    }
}
