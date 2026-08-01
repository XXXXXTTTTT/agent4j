package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
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
                .containsEntry(ReviewerNode.APPROVED_KEY, "true")
                .containsEntry(ReviewerNode.SUMMARY_KEY, "测试和页面正常")
                .containsEntry(ReviewerNode.FEEDBACK_KEY, "无需修改")
                .containsEntry(ReviewerNode.MODEL_KEY, "vision-model")
                .doesNotContainKey(ReviewerNode.ERROR_KEY);
        assertThat(result.trace()).containsExactly("reviewer");
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
