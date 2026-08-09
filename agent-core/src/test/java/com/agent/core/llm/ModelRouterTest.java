package com.agent.core.llm;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.engine.StateGraph;
import com.agent.core.observability.ModelCallSpan;
import com.agent.core.observability.ModelCallStart;
import com.agent.core.observability.ModelCallSuccess;
import com.agent.core.observability.ModelCallObserver;
import com.agent.core.observability.ModelUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelRouterTest {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<LlmClient> clients = new ArrayList<>();
    private final List<MockRestServiceServer> servers = new ArrayList<>();
    private int endpointSequence;

    @AfterEach
    void closeClientsAndVerifyRequests() {
        clients.forEach(LlmClient::close);
        servers.forEach(MockRestServiceServer::verify);
    }

    @Test
    void routesEveryTaskTypeToItsExactEndpointAndModel() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture vision = endpoint("vision-primary", "vision-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectSuccess(code, "code-result");
        expectSuccess(vision, "vision-result");
        expectSuccess(classification, "quick-result");
        ModelRouter router = router(code.endpoint(), vision.endpoint(), classification.endpoint());

        RoutedCompletion codeResult = router.complete(TaskType.CODE, request());
        RoutedCompletion visionResult = router.complete(TaskType.VISION, request());
        RoutedCompletion classificationResult =
                router.complete(TaskType.QUICK_CLASSIFICATION, request());

        assertRoutedTo(codeResult, "code-primary", "code-model", "code-result");
        assertRoutedTo(visionResult, "vision-primary", "vision-model", "vision-result");
        assertRoutedTo(
                classificationResult,
                "classification-primary",
                "quick-model",
                "quick-result");
    }

    @Test
    void fallsBackInOrderAfterHttpFailure() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture primary = endpoint("vision-primary", "vision-primary-model");
        EndpointFixture fallback = endpoint("vision-fallback", "vision-fallback-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectBadGateway(primary);
        expectSuccess(fallback, "fallback-result");
        ModelRouter router = router(
                code.endpoint(),
                List.of(primary.endpoint(), fallback.endpoint()),
                classification.endpoint());

        RoutedCompletion result = router.complete(TaskType.VISION, request());

        assertRoutedTo(
                result,
                "vision-fallback",
                "vision-fallback-model",
                "fallback-result");
        assertThat(primary.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void skipsEndpointWithoutToolCapabilityBeforeHttpCall() {
        EndpointFixture code = endpoint(
                "code-primary",
                "code-model",
                Set.of(InferenceCapability.CHAT_COMPLETIONS));
        EndpointFixture fallback = endpoint("code-fallback", "fallback-model");
        EndpointFixture vision = endpoint("vision-primary", "vision-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectToolSuccess(fallback, "fallback-result");
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(code.endpoint(), fallback.endpoint()),
                TaskType.VISION, List.of(vision.endpoint()),
                TaskType.QUICK_CLASSIFICATION, List.of(classification.endpoint())));

        RoutedCompletion result = router.complete(TaskType.CODE, toolRequest());

        assertRoutedTo(result, "code-fallback", "fallback-model", "fallback-result");
        assertThat(code.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void skipsEndpointWithoutVisionCapabilityBeforeHttpCall() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture vision = endpoint(
                "vision-primary",
                "vision-model",
                Set.of(InferenceCapability.CHAT_COMPLETIONS));
        EndpointFixture fallback = endpoint("vision-fallback", "fallback-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectSuccess(fallback, "fallback-result");
        ModelRouter router = router(
                code.endpoint(),
                List.of(vision.endpoint(), fallback.endpoint()),
                classification.endpoint());

        RoutedCompletion result = router.complete(TaskType.VISION, request());

        assertRoutedTo(result, "vision-fallback", "fallback-model", "fallback-result");
        assertThat(vision.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void fallsBackWhenEndpointBudgetRejectsWithoutChangingCircuitState() {
        InferenceAdmissionController exhaustedController = new InferenceAdmissionController(
                new InferenceBudget(1, 10, Duration.ZERO));
        EndpointFixture code = endpoint(
                "code-primary",
                "code-model",
                InferenceServiceContract.allCapabilities(),
                exhaustedController);
        EndpointFixture fallback = endpoint("code-fallback", "fallback-model");
        EndpointFixture vision = endpoint("vision-primary", "vision-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectSuccess(fallback, "fallback-result");
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(code.endpoint(), fallback.endpoint()),
                TaskType.VISION, List.of(vision.endpoint()),
                TaskType.QUICK_CLASSIFICATION, List.of(classification.endpoint())));

        InferencePermit held = exhaustedController.acquire();
        try {
            RoutedCompletion result = router.complete(TaskType.CODE, request());
            assertRoutedTo(result, "code-fallback", "fallback-model", "fallback-result");
            assertThat(code.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
            assertThat(exhaustedController.snapshot().concurrencyRejections()).isEqualTo(1);
        } finally {
            held.close();
        }
    }

    @Test
    void observesEachEndpointAttemptAndTokenUsage() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture primary = endpoint("vision-primary", "vision-primary-model");
        EndpointFixture fallback = endpoint("vision-fallback", "vision-fallback-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectBadGateway(primary);
        expectSuccessWithUsage(fallback, "fallback-result", 11, 7, 18);
        RecordingObserver observer = new RecordingObserver();
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(code.endpoint()),
                TaskType.VISION, List.of(primary.endpoint(), fallback.endpoint()),
                TaskType.QUICK_CLASSIFICATION, List.of(classification.endpoint())), observer);

        RoutedCompletion result = router.complete(TaskType.VISION, request());

        assertRoutedTo(result, "vision-fallback", "vision-fallback-model", "fallback-result");
        assertThat(observer.starts()).extracting(ModelCallStart::endpointName)
                .containsExactly("vision-primary", "vision-fallback");
        assertThat(observer.starts()).extracting(ModelCallStart::requestedModel)
                .containsExactly("vision-primary-model", "vision-fallback-model");
        assertThat(observer.failures()).hasSize(1);
        assertThat(observer.successes()).containsExactly(new ModelCallSuccess(
                Optional.of("vision-fallback-model"),
                Optional.of(new ModelUsage(11, 7, 18))));
        assertThat(observer.closeCount()).isEqualTo(2);
    }

    @Test
    void contributesSuccessfulModelUsageToGraphTokenBudget() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture vision = endpoint("vision-primary", "vision-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectSuccessWithUsage(code, "code-result", 11, 7, 18);
        ModelRouter router = router(code.endpoint(), vision.endpoint(), classification.endpoint());
        ExecutionBudget budget = new ExecutionBudget(
                Duration.ofSeconds(2), Duration.ofSeconds(1), 20, 2, 2);

        try (StateGraph graph = new StateGraph(budget, InterruptPolicy.never())) {
            graph.addNode("model", state -> {
                        router.complete(TaskType.CODE, request());
                        return state.withVariable(
                                "tokens", Long.toString(NodeExecutionContext.consumedTokens()));
                    })
                    .addEdge("model", StateGraph.END)
                    .setEntryPoint("model");

            assertThat(graph.execute(AgentState.empty()).variables())
                    .containsEntry("tokens", "18");
        }
    }

    @Test
    void mapsMissingUsageToEmptyOptional() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture vision = endpoint("vision-primary", "vision-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectSuccess(code, "code-result");
        RecordingObserver observer = new RecordingObserver();
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(code.endpoint()),
                TaskType.VISION, List.of(vision.endpoint()),
                TaskType.QUICK_CLASSIFICATION, List.of(classification.endpoint())), observer);

        router.complete(TaskType.CODE, request());

        assertThat(observer.successes()).containsExactly(new ModelCallSuccess(
                Optional.of("code-model"), Optional.empty()));
    }

    @Test
    void isolatesObserverStartFailureFromSuccessfulRouting() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture vision = endpoint("vision-primary", "vision-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectSuccess(code, "code-result");
        ModelCallObserver observer = ignored -> {
            throw new IllegalStateException("observer start failed");
        };
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(code.endpoint()),
                TaskType.VISION, List.of(vision.endpoint()),
                TaskType.QUICK_CLASSIFICATION, List.of(classification.endpoint())), observer);

        RoutedCompletion result = router.complete(TaskType.CODE, request());

        assertRoutedTo(result, "code-primary", "code-model", "code-result");
    }

    @Test
    void isolatesSpanCallbackFailuresFromFallbackRouting() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture primary = endpoint("vision-primary", "vision-primary-model");
        EndpointFixture fallback = endpoint("vision-fallback", "vision-fallback-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectBadGateway(primary);
        expectSuccess(fallback, "fallback-result");
        ModelCallObserver observer = ignored -> new ModelCallSpan() {
            @Override
            public void succeed(ModelCallSuccess success) {
                throw new IllegalStateException("observer success failed");
            }

            @Override
            public void fail(Throwable failure) {
                throw new IllegalStateException("observer failure failed");
            }

            @Override
            public void close() {
                throw new IllegalStateException("observer close failed");
            }
        };
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(code.endpoint()),
                TaskType.VISION, List.of(primary.endpoint(), fallback.endpoint()),
                TaskType.QUICK_CLASSIFICATION, List.of(classification.endpoint())), observer);

        RoutedCompletion result = router.complete(TaskType.VISION, request());

        assertRoutedTo(result, "vision-fallback", "vision-fallback-model", "fallback-result");
    }

    @Test
    void skipsOpenCircuitAndUsesFallbackWithoutHttpCall() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture primary = endpoint("vision-primary", "vision-primary-model");
        EndpointFixture fallback = endpoint("vision-fallback", "vision-fallback-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        primary.circuitBreaker().transitionToOpenState();
        expectSuccess(fallback, "fallback-result");
        ModelRouter router = router(
                code.endpoint(),
                List.of(primary.endpoint(), fallback.endpoint()),
                classification.endpoint());

        RoutedCompletion result = router.complete(TaskType.VISION, request());

        assertThat(result.endpointName()).isEqualTo("vision-fallback");
        assertThat(primary.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void recordsEmptyChoicesAsCircuitFailureAndFallsBack() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture primary = endpoint("vision-primary", "vision-primary-model");
        EndpointFixture fallback = endpoint("vision-fallback", "vision-fallback-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        expectEmptyChoices(primary);
        expectSuccess(fallback, "fallback-result");
        ModelRouter router = router(
                code.endpoint(),
                List.of(primary.endpoint(), fallback.endpoint()),
                classification.endpoint());

        RoutedCompletion result = router.complete(TaskType.VISION, request());

        assertThat(result.endpointName()).isEqualTo("vision-fallback");
        assertThat(primary.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void aggregatesOpenCircuitFailuresInEndpointOrder() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture primary = endpoint("vision-primary", "vision-primary-model");
        EndpointFixture fallback = endpoint("vision-fallback", "vision-fallback-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");
        primary.circuitBreaker().transitionToOpenState();
        fallback.circuitBreaker().transitionToOpenState();
        ModelRouter router = router(
                code.endpoint(),
                List.of(primary.endpoint(), fallback.endpoint()),
                classification.endpoint());

        assertThatThrownBy(() -> router.complete(TaskType.VISION, request()))
                .isInstanceOf(ModelRoutingException.class)
                .hasMessageContaining("VISION")
                .satisfies(exception -> {
                    Throwable[] failures = exception.getSuppressed();
                    assertThat(failures).hasSize(2);
                    assertThat(failures[0])
                            .isInstanceOf(ModelEndpointException.class)
                            .hasMessageContaining("vision-primary")
                            .hasMessageContaining("vision-primary-model")
                            .hasCauseInstanceOf(CallNotPermittedException.class);
                    assertThat(failures[1])
                            .isInstanceOf(ModelEndpointException.class)
                            .hasMessageContaining("vision-fallback")
                            .hasMessageContaining("vision-fallback-model")
                            .hasCauseInstanceOf(CallNotPermittedException.class);
                });
    }

    @Test
    void rejectsIncompleteRoutesAndInvalidDependencies() {
        EndpointFixture code = endpoint("code-primary", "code-model");
        EndpointFixture vision = endpoint("vision-primary", "vision-model");
        EndpointFixture classification = endpoint("classification-primary", "quick-model");

        assertThatThrownBy(() -> new ModelRouter(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelRouter(Map.of(
                TaskType.CODE, List.of(code.endpoint()),
                TaskType.VISION, List.of(vision.endpoint()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QUICK_CLASSIFICATION");
        assertThatThrownBy(() -> new ModelRouter(Map.of(
                TaskType.CODE, List.of(code.endpoint()),
                TaskType.VISION, List.of(),
                TaskType.QUICK_CLASSIFICATION, List.of(classification.endpoint()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VISION");
        assertThatThrownBy(() -> new ModelEndpoint(
                " ", "model", code.client(), CircuitBreaker.ofDefaults("blank-name")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new ModelEndpoint(
                "endpoint", " ", code.client(), CircuitBreaker.ofDefaults("blank-model")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(() -> new ModelEndpoint(
                "endpoint", "model", null, CircuitBreaker.ofDefaults("null-client")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("client");
        assertThatThrownBy(() -> new ModelEndpoint(
                "endpoint", "model", code.client(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("circuitBreaker");

        ModelRouter router = router(code.endpoint(), vision.endpoint(), classification.endpoint());
        assertThatThrownBy(() -> router.complete(null, request()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskType");
        assertThatThrownBy(() -> router.complete(TaskType.CODE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("request");
    }

    @Test
    void freezesRouterAndRequestCollections() {
        EndpointFixture shared = endpoint("shared-primary", "shared-model");
        List<ModelEndpoint> endpointList = new ArrayList<>(List.of(shared.endpoint()));
        EnumMap<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        for (TaskType taskType : TaskType.values()) {
            routes.put(taskType, endpointList);
        }
        ModelRouter router = new ModelRouter(routes);
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("route")));
        List<LlmClient.Tool> tools = new ArrayList<>();
        ModelRequest request = new ModelRequest(messages, tools, null, null);

        endpointList.clear();
        routes.clear();
        messages.clear();
        tools.add(LlmClient.Tool.function(
                "late-tool", "late", objectMapper.createObjectNode()));
        expectSuccess(shared, "frozen-result");

        RoutedCompletion result = router.complete(TaskType.CODE, request);

        assertThat(result.endpointName()).isEqualTo("shared-primary");
        assertThat(request.messages()).containsExactly(ChatMessage.user("route"));
        assertThat(request.tools()).isEmpty();
        assertThatThrownBy(() -> request.messages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.tools().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private ModelRouter router(
            ModelEndpoint code,
            ModelEndpoint vision,
            ModelEndpoint classification) {
        return router(code, List.of(vision), classification);
    }

    private ModelRouter router(
            ModelEndpoint code,
            List<ModelEndpoint> vision,
            ModelEndpoint classification) {
        return new ModelRouter(Map.of(
                TaskType.CODE, List.of(code),
                TaskType.VISION, vision,
                TaskType.QUICK_CLASSIFICATION, List.of(classification)));
    }

    private ModelRequest request() {
        return new ModelRequest(
                List.of(ChatMessage.user("route")),
                List.of(),
                null,
                null);
    }

    private ModelRequest toolRequest() {
        return new ModelRequest(
                List.of(ChatMessage.user("route")),
                List.of(LlmClient.Tool.function(
                        "lookup", "lookup data", objectMapper.createObjectNode())),
                null,
                null);
    }

    private EndpointFixture endpoint(String name, String model) {
        return endpoint(name, model, InferenceServiceContract.allCapabilities(),
                InferenceAdmissionController.unlimited());
    }

    private EndpointFixture endpoint(
            String name,
            String model,
            Set<InferenceCapability> capabilities) {
        return endpoint(
                name,
                model,
                capabilities,
                InferenceAdmissionController.unlimited());
    }

    private EndpointFixture endpoint(
            String name,
            String model,
            Set<InferenceCapability> capabilities,
            InferenceAdmissionController admissionController) {
        endpointSequence++;
        String baseUrl = "https://endpoint-" + endpointSequence + ".test";
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(
                builder.build(), objectMapper, CHAT_COMPLETIONS_PATH);
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults(
                name + "-" + endpointSequence);
        clients.add(client);
        servers.add(server);
        return new EndpointFixture(
                new ModelEndpoint(
                        name,
                        model,
                        client,
                        circuitBreaker,
                        new InferenceServiceContract(
                                name,
                                model,
                                InferenceProtocol.OPENAI_CHAT_COMPLETIONS,
                                capabilities),
                        admissionController),
                baseUrl,
                server,
                client,
                circuitBreaker);
    }

    private void expectSuccess(EndpointFixture fixture, String contentText) {
        fixture.server().expect(once(), requestTo(fixture.baseUrl() + CHAT_COMPLETIONS_PATH))
                .andExpect(content().json("""
                        {
                          "model": "%s",
                          "messages": [{"role": "user", "content": "route"}],
                          "tools": [],
                          "stream": false
                        }
                        """.formatted(fixture.endpoint().model()), true))
                .andRespond(withSuccess("""
                        {
                          "id": "response-id",
                          "object": "chat.completion",
                          "created": 1720000000,
                          "model": "%s",
                          "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "%s"},
                            "finish_reason": "stop"
                          }]
                        }
                        """.formatted(fixture.endpoint().model(), contentText),
                        MediaType.APPLICATION_JSON));
    }

    private void expectToolSuccess(EndpointFixture fixture, String contentText) {
        fixture.server().expect(once(), requestTo(fixture.baseUrl() + CHAT_COMPLETIONS_PATH))
                .andExpect(content().json("""
                        {
                          "model": "%s",
                          "messages": [{"role": "user", "content": "route"}],
                          "tools": [{
                            "type": "function",
                            "function": {
                              "name": "lookup",
                              "description": "lookup data",
                              "parameters": {}
                            }
                          }],
                          "stream": false
                        }
                        """.formatted(fixture.endpoint().model()), true))
                .andRespond(withSuccess("""
                        {
                          "id": "response-id",
                          "object": "chat.completion",
                          "created": 1720000000,
                          "model": "%s",
                          "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "%s"},
                            "finish_reason": "stop"
                          }]
                        }
                        """.formatted(fixture.endpoint().model(), contentText),
                        MediaType.APPLICATION_JSON));
    }

    private void expectSuccessWithUsage(
            EndpointFixture fixture,
            String contentText,
            int promptTokens,
            int completionTokens,
            int totalTokens) {
        fixture.server().expect(once(), requestTo(fixture.baseUrl() + CHAT_COMPLETIONS_PATH))
                .andExpect(content().json("""
                        {
                          "model": "%s",
                          "messages": [{"role": "user", "content": "route"}],
                          "tools": [],
                          "stream": false
                        }
                        """.formatted(fixture.endpoint().model()), true))
                .andRespond(withSuccess("""
                        {
                          "id": "response-id",
                          "object": "chat.completion",
                          "created": 1720000000,
                          "model": "%s",
                          "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "%s"},
                            "finish_reason": "stop"
                          }],
                          "usage": {
                            "prompt_tokens": %d,
                            "completion_tokens": %d,
                            "total_tokens": %d
                          }
                        }
                        """.formatted(
                                fixture.endpoint().model(),
                                contentText,
                                promptTokens,
                                completionTokens,
                                totalTokens),
                        MediaType.APPLICATION_JSON));
    }

    private void expectBadGateway(EndpointFixture fixture) {
        fixture.server().expect(once(), requestTo(fixture.baseUrl() + CHAT_COMPLETIONS_PATH))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"upstream unavailable\"}}"));
    }

    private void expectEmptyChoices(EndpointFixture fixture) {
        fixture.server().expect(once(), requestTo(fixture.baseUrl() + CHAT_COMPLETIONS_PATH))
                .andRespond(withSuccess("""
                        {
                          "id": "empty-response",
                          "object": "chat.completion",
                          "created": 1720000000,
                          "model": "%s",
                          "choices": []
                        }
                        """.formatted(fixture.endpoint().model()),
                        MediaType.APPLICATION_JSON));
    }

    private static void assertRoutedTo(
            RoutedCompletion result,
            String endpointName,
            String model,
            String contentText) {
        assertThat(result.endpointName()).isEqualTo(endpointName);
        assertThat(result.model()).isEqualTo(model);
        ChatMessage message = result.response().choices().getFirst().message();
        assertThat(message.content()).isEqualTo(new ChatMessage.TextContent(contentText));
    }

    private record EndpointFixture(
            ModelEndpoint endpoint,
            String baseUrl,
            MockRestServiceServer server,
            LlmClient client,
            CircuitBreaker circuitBreaker) {
    }

    private static final class RecordingObserver implements ModelCallObserver {

        private final List<ModelCallStart> starts = new ArrayList<>();
        private final List<ModelCallSuccess> successes = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
        private int closeCount;

        @Override
        public ModelCallSpan start(ModelCallStart start) {
            starts.add(start);
            return new ModelCallSpan() {
                @Override
                public void succeed(ModelCallSuccess success) {
                    successes.add(success);
                }

                @Override
                public void fail(Throwable failure) {
                    failures.add(failure);
                }

                @Override
                public void close() {
                    closeCount++;
                }
            };
        }

        private List<ModelCallStart> starts() {
            return starts;
        }

        private List<ModelCallSuccess> successes() {
            return successes;
        }

        private List<Throwable> failures() {
            return failures;
        }

        private int closeCount() {
            return closeCount;
        }
    }
}
