package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.StateGraph;
import com.agent.core.harness.HarnessEvent;
import com.agent.core.harness.HarnessEventType;
import com.agent.core.harness.HarnessHookChain;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.BrowserScreenshot;
import com.agent.sandbox.browser.NavigationResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReviewerNodeTest {

    private static final String BASE_URL = "https://vision-gateway.test";
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final URI PAGE_URI = URI.create("https://application.test/review");
    private static final Duration BROWSER_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final TestBrowserAutomation browser = new TestBrowserAutomation();
    private LlmClient client;
    private MockRestServiceServer server;
    private CircuitBreaker visionCircuitBreaker;

    @AfterEach
    void closeClientAndVerifyRequests() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.verify();
        }
    }

    @Test
    void combinesBrowserAndCompleteOpsEvidenceIntoMultimodalReview() throws Exception {
        ReviewerNode node = reviewerNode();
        expectModelResponse(
                textContent("{\"approved\":true,\"summary\":\"测试和页面正常\",\"feedback\":\"无需修改\"}"),
                "data:image/png;base64,AQID",
                "<html><body>ready</body></html>",
                OpsNode.EXIT_CODE_KEY,
                OpsNode.STDOUT_KEY,
                OpsNode.STDERR_KEY,
                OpsNode.TIMED_OUT_KEY);

        AgentState result = node.execute(completeOpsState());

        assertThat(browser.navigatedUri).isEqualTo(PAGE_URI);
        assertThat(browser.navigationTimeout).isEqualTo(BROWSER_TIMEOUT);
        assertThat(browser.screenshotTimeout).isEqualTo(BROWSER_TIMEOUT);
        assertThat(result.variables())
                .containsEntry(ReviewerNode.FINAL_URL_KEY, PAGE_URI.toString())
                .containsEntry(
                        ReviewerNode.DOM_KEY,
                        "<html><body>ready</body></html>")
                .containsEntry(
                        ReviewerNode.SCREENSHOT_DATA_URL_KEY,
                        "data:image/png;base64,AQID")
                .containsEntry(ReviewerNode.APPROVED_KEY, "true")
                .containsEntry(ReviewerNode.SUMMARY_KEY, "测试和页面正常")
                .containsEntry(ReviewerNode.FEEDBACK_KEY, "无需修改")
                .containsEntry(ReviewerNode.MODEL_KEY, "vision-model")
                .doesNotContainKey(ReviewerNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("reviewer");
    }

    @Test
    void publishesBrowserEvidenceThroughHarnessToolBoundary() throws Exception {
        ReviewerNode node = reviewerNode();
        expectModelResponse(textContent(
                "{\"approved\":true,\"summary\":\"正常\",\"feedback\":\"无\"}"));
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        ExecutionBudget budget = new ExecutionBudget(
                Duration.ofSeconds(5), Duration.ofSeconds(2), 100, 2, 2);

        try (StateGraph graph = new StateGraph(
                budget,
                InterruptPolicy.never(),
                new HarnessHookChain(List.of(events::add)))) {
            graph.addNode("reviewer", node)
                    .addEdge("reviewer", StateGraph.END)
                    .setEntryPoint("reviewer");
            graph.execute(completeOpsState());
        }

        assertThat(events).filteredOn(event ->
                        event.eventType() == HarnessEventType.BEFORE_TOOL
                                || event.eventType() == HarnessEventType.AFTER_TOOL)
                .extracting(HarnessEvent::eventType)
                .containsExactly(HarnessEventType.BEFORE_TOOL, HarnessEventType.AFTER_TOOL);
        assertThat(events).filteredOn(event -> event.metadata().containsKey("toolName"))
                .allSatisfy(event -> assertThat(event.metadata())
                        .containsEntry("toolName", "browser.evidence")
                        .containsEntry("url", PAGE_URI.toString()));
    }

    @Test
    void treatsRejectedDecisionAsNormalResult() throws Exception {
        ReviewerNode node = reviewerNode();
        expectModelResponse(textContent(
                "{\"approved\":false,\"summary\":\"存在问题\",\"feedback\":\"修复页面\"}"));

        AgentState result = node.execute(completeOpsState());

        assertThat(result.variables())
                .containsEntry(ReviewerNode.APPROVED_KEY, "false")
                .containsEntry(ReviewerNode.SUMMARY_KEY, "存在问题")
                .containsEntry(ReviewerNode.FEEDBACK_KEY, "修复页面")
                .doesNotContainKey(ReviewerNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("reviewer");
    }

    @Test
    void acceptsOpsErrorAsReviewEvidence() throws Exception {
        ReviewerNode node = reviewerNode();
        expectModelResponse(
                textContent("{\"approved\":false,\"summary\":\"命令失败\",\"feedback\":\"修复命令\"}"),
                OpsNode.ERROR_KEY,
                "terminal stack");

        AgentState result = node.execute(AgentState.empty()
                .withVariable(ReviewerNode.URL_KEY, PAGE_URI.toString())
                .withVariable(OpsNode.ERROR_KEY, "terminal stack"));

        assertThat(result.variables())
                .containsEntry(ReviewerNode.APPROVED_KEY, "false")
                .containsEntry(ReviewerNode.MODEL_KEY, "vision-model")
                .doesNotContainKey(ReviewerNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("reviewer");
    }

    @Test
    void reviewsCodeAndOpsEvidenceWithoutBrowserUrl() throws Exception {
        ReviewerNode node = reviewerNode();
        expectModelResponse(
                textContent("{\"approved\":true,\"summary\":\"代码测试通过\",\"feedback\":\"无需修改\"}"),
                OpsNode.EXIT_CODE_KEY,
                OpsNode.STDOUT_KEY,
                "cat value.txt");

        AgentState result = node.execute(AgentState.empty()
                .withVariable(OpsNode.EXIT_CODE_KEY, "0")
                .withVariable(OpsNode.STDOUT_KEY, "after")
                .withVariable(OpsNode.STDERR_KEY, "")
                .withVariable(OpsNode.TIMED_OUT_KEY, "false")
                .withVariable(CoderNode.SUMMARY_KEY, "将标签改为平方根并保留两位小数")
                .withVariable(CoderNode.UPDATED_FILES_KEY, "src/main/java/demo/NumberLabel.java")
                .withVariable(CoderNode.UNIFIED_DIFF_KEY, "diff --git a/value.txt b/value.txt")
                .withVariable(OpsNode.COMMAND_KEY, "cat value.txt"));

        assertThat(browser.navigatedUri).isNull();
        assertThat(result.variables())
                .containsEntry(ReviewerNode.APPROVED_KEY, "true")
                .containsEntry(ReviewerNode.SUMMARY_KEY, "代码测试通过")
                .containsEntry(ReviewerNode.RESPONSE_KEY,
                        "{\"approved\":true,\"summary\":\"代码测试通过\",\"feedback\":\"无需修改\"}")
                .containsKey(ReviewerNode.REQUEST_KEY)
                .doesNotContainKey(ReviewerNode.ERROR_KEY);
        assertThat(result.variables().get(PlannerNode.FINAL_RESPONSE_KEY))
                .contains(
                        "src/main/java/demo/NumberLabel.java",
                        "将标签改为平方根并保留两位小数",
                        "cat value.txt",
                        "退出码：0",
                        "代码测试通过",
                        "无需修改");
        assertThat(result.trace()).containsExactly("reviewer");
    }

    @Test
    void routesCodeOnlyEvidenceToCodeModel() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH);
        ModelEndpoint codeEndpoint = new ModelEndpoint(
                "code-review-endpoint",
                "code-review-model",
                client,
                CircuitBreaker.ofDefaults("reviewer-code-route"));
        ModelEndpoint visionEndpoint = new ModelEndpoint(
                "vision-review-endpoint",
                "vision-model",
                client,
                CircuitBreaker.ofDefaults("reviewer-vision-route"));
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(codeEndpoint),
                TaskType.VISION, List.of(visionEndpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(codeEndpoint)));
        ReviewerNode node = new ReviewerNode(
                browser, router, objectMapper, BROWSER_TIMEOUT);
        server.expect(once(), requestTo(BASE_URL + CHAT_COMPLETIONS_PATH))
                .andExpect(content().string(containsString(
                        "\"model\":\"code-review-model\"")))
                .andRespond(withSuccess(
                        responseJson(textContent(
                                "{\"approved\":true,\"summary\":\"代码测试通过\",\"feedback\":\"无需修改\"}")),
                        MediaType.APPLICATION_JSON));

        AgentState result = node.execute(AgentState.empty()
                .withVariable(OpsNode.EXIT_CODE_KEY, "0")
                .withVariable(OpsNode.STDOUT_KEY, "tests passed")
                .withVariable(OpsNode.STDERR_KEY, "")
                .withVariable(OpsNode.TIMED_OUT_KEY, "false")
                .withVariable(CoderNode.UNIFIED_DIFF_KEY, "diff --git a/value.txt b/value.txt"));

        assertThat(result.variables())
                .containsEntry(ReviewerNode.MODEL_KEY, "code-review-model")
                .containsEntry(ReviewerNode.APPROVED_KEY, "true")
                .doesNotContainKey(ReviewerNode.ERROR_KEY);
    }

    @Test
    void recordsMissingOpsEvidenceAndRelativeUrlAsFullStacks() {
        ReviewerNode missingOpsNode = reviewerNode();

        AgentState missingOps = missingOpsNode.execute(AgentState.empty()
                .withVariable(ReviewerNode.URL_KEY, PAGE_URI.toString()));

        assertReviewFailure(missingOps, "java.lang.IllegalArgumentException", "Ops");

        ReviewerNode relativeUrlNode = reviewerNode();
        AgentState relativeUrl = relativeUrlNode.execute(completeOpsState()
                .withVariable(ReviewerNode.URL_KEY, "/relative"));

        assertReviewFailure(relativeUrl, "java.lang.IllegalArgumentException", "绝对 URI");
    }

    @Test
    void rejectsMarkdownFenceAndUnknownJsonFields() throws Exception {
        ReviewerNode fencedNode = reviewerNode();
        expectModelResponse(textContent("""
                ```json
                {"approved":true,"summary":"正常","feedback":"无"}
                ```
                """));
        ReviewerNode unknownFieldNode = reviewerNode();
        expectModelResponse(textContent(
                "{\"approved\":true,\"summary\":\"正常\",\"feedback\":\"无\",\"extra\":\"拒绝\"}"));

        AgentState fenced = fencedNode.execute(completeOpsState());
        AgentState unknownField = unknownFieldNode.execute(completeOpsState());

        assertReviewFailure(fenced, "JsonParseException", "Unexpected character ('`'");
        assertReviewFailure(unknownField, "UnrecognizedPropertyException", "extra");
    }

    @Test
    void rejectsNonTextAssistantContent() throws Exception {
        ReviewerNode node = reviewerNode();
        ArrayNode multimodal = objectMapper.createArrayNode();
        multimodal.add(objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", "not a plain text response"));
        expectModelResponse(multimodal);

        AgentState result = node.execute(completeOpsState());

        assertReviewFailure(result, "java.lang.IllegalStateException", "TextContent");
    }

    @Test
    void rejectsCoercedReviewerDecisionFieldTypes() throws Exception {
        ReviewerNode booleanStringNode = reviewerNode();
        expectModelResponse(textContent(
                "{\"approved\":\"true\",\"summary\":\"正常\",\"feedback\":\"无\"}"));
        ReviewerNode numericSummaryNode = reviewerNode();
        expectModelResponse(textContent(
                "{\"approved\":true,\"summary\":123,\"feedback\":\"无\"}"));

        AgentState booleanString = booleanStringNode.execute(completeOpsState());
        AgentState numericSummary = numericSummaryNode.execute(completeOpsState());

        assertReviewFailure(booleanString, "IllegalArgumentException", "approved");
        assertReviewFailure(numericSummary, "IllegalArgumentException", "summary");
    }

    @Test
    void preservesBrowserFutureFailureStack() {
        browser.navigationFailure = new IllegalStateException("browser failed");
        ReviewerNode node = reviewerNode();

        AgentState result = node.execute(completeOpsState());

        assertReviewFailure(result, "java.util.concurrent.ExecutionException", "browser failed");
    }

    @Test
    void preservesRoutingSuppressedFailuresInStack() {
        ReviewerNode node = reviewerNode();
        visionCircuitBreaker.transitionToOpenState();

        AgentState result = node.execute(completeOpsState());

        assertReviewFailure(result, "ModelRoutingException", "VISION");
        assertThat(result.variables())
                .containsEntry(ReviewerNode.FINAL_URL_KEY, PAGE_URI.toString())
                .containsEntry(
                        ReviewerNode.DOM_KEY,
                        "<html><body>ready</body></html>")
                .containsEntry(
                        ReviewerNode.SCREENSHOT_DATA_URL_KEY,
                        "data:image/png;base64,AQID");
        assertThat(result.variables().get(ReviewerNode.ERROR_KEY))
                .contains("Suppressed: com.agent.core.llm.ModelEndpointException")
                .contains("CallNotPermittedException");
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        ModelRouter router = modelRouter();

        assertThatThrownBy(() -> new ReviewerNode(
                null, router, objectMapper, BROWSER_TIMEOUT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReviewerNode(
                browser, null, objectMapper, BROWSER_TIMEOUT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReviewerNode(
                browser, router, null, BROWSER_TIMEOUT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReviewerNode(
                browser, router, objectMapper, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReviewerNode(
                browser, router, objectMapper, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewerNode(
                browser, router, objectMapper, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ReviewerNode reviewerNode() {
        return new ReviewerNode(browser, modelRouter(), objectMapper, BROWSER_TIMEOUT);
    }

    private ModelRouter modelRouter() {
        if (client != null) {
            return routerForCurrentClient();
        }
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH);
        visionCircuitBreaker = CircuitBreaker.ofDefaults("reviewer-vision");
        return routerForCurrentClient();
    }

    private ModelRouter routerForCurrentClient() {
        ModelEndpoint endpoint = new ModelEndpoint(
                "vision-endpoint", "vision-model", client, visionCircuitBreaker);
        return new ModelRouter(Map.of(
                TaskType.CODE, List.of(endpoint),
                TaskType.VISION, List.of(endpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(endpoint)));
    }

    private AgentState completeOpsState() {
        return AgentState.empty()
                .withVariable(ReviewerNode.URL_KEY, PAGE_URI.toString())
                .withVariable(OpsNode.EXIT_CODE_KEY, "0")
                .withVariable(OpsNode.STDOUT_KEY, "tests passed")
                .withVariable(OpsNode.STDERR_KEY, "")
                .withVariable(OpsNode.TIMED_OUT_KEY, "false");
    }

    private void expectModelResponse(JsonNode assistantContent, String... requiredBodyText)
            throws Exception {
        var expectation = server.expect(
                        once(), requestTo(BASE_URL + CHAT_COMPLETIONS_PATH))
                .andExpect(content().string(containsString("\"model\":\"vision-model\"")));
        for (String required : requiredBodyText) {
            expectation.andExpect(content().string(containsString(required)));
        }
        expectation.andRespond(withSuccess(
                responseJson(assistantContent), MediaType.APPLICATION_JSON));
    }

    private String responseJson(JsonNode assistantContent) throws Exception {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", "review-response");
        response.put("object", "chat.completion");
        response.put("created", 1720000000L);
        response.put("model", "vision-model");
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.set("content", assistantContent);
        choice.put("finish_reason", "stop");
        return objectMapper.writeValueAsString(response);
    }

    private JsonNode textContent(String value) {
        return objectMapper.getNodeFactory().textNode(value);
    }

    private static void assertReviewFailure(
            AgentState state,
            String exceptionType,
            String detail) {
        assertThat(state.variables().get(ReviewerNode.ERROR_KEY))
                .contains(exceptionType)
                .contains(detail)
                .contains("at ");
        assertThat(state.variables()).doesNotContainKeys(
                ReviewerNode.APPROVED_KEY,
                ReviewerNode.SUMMARY_KEY,
                ReviewerNode.FEEDBACK_KEY,
                ReviewerNode.MODEL_KEY);
        assertThat(state.trace()).containsExactly("reviewer");
    }

    private static final class TestBrowserAutomation implements BrowserAutomation {

        private URI navigatedUri;
        private Duration navigationTimeout;
        private Duration screenshotTimeout;
        private RuntimeException navigationFailure;

        @Override
        public CompletableFuture<NavigationResult> navigate(URI url, Duration timeout) {
            navigatedUri = url;
            navigationTimeout = timeout;
            if (navigationFailure != null) {
                return CompletableFuture.failedFuture(navigationFailure);
            }
            if (!url.isAbsolute()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("reviewer.url 必须是绝对 URI"));
            }
            return CompletableFuture.completedFuture(new NavigationResult(
                    url, url, OptionalInt.of(200)));
        }

        @Override
        public CompletableFuture<Void> click(String selector, Duration timeout) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<String> extractDom() {
            return CompletableFuture.completedFuture("<html><body>ready</body></html>");
        }

        @Override
        public CompletableFuture<String> extractDom(Duration timeout) {
            return CompletableFuture.completedFuture("<html><body>ready</body></html>");
        }

        @Override
        public CompletableFuture<BrowserScreenshot> screenshot(Duration timeout) {
            screenshotTimeout = timeout;
            return CompletableFuture.completedFuture(
                    new BrowserScreenshot(new byte[] {1, 2, 3}, "image/png"));
        }

        @Override
        public void close() {
        }
    }
}
