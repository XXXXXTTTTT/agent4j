package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.StateGraph;
import com.agent.core.gui.BrowserSessionRegistry;
import com.agent.core.harness.HarnessEvent;
import com.agent.core.harness.HarnessEventType;
import com.agent.core.harness.HarnessHook;
import com.agent.core.harness.HarnessHookChain;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.JacksonToolSchemaValidator;
import com.agent.core.tool.ToolAuditEvent;
import com.agent.core.tool.builtin.BrowserToolDefinitions;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.BrowserEvidence;
import com.agent.sandbox.browser.BrowserEvidenceSelector;
import com.agent.sandbox.browser.BrowserScreenshot;
import com.agent.sandbox.browser.NavigationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GuiAgentNodeTest {

    private static final String BASE_URL = "https://llm.gui.test";
    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ExecutionBudget BUDGET = new ExecutionBudget(
            Duration.ofSeconds(10), Duration.ofSeconds(5), 10_000, 5, 3);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LlmClient client;
    private MockRestServiceServer server;

    @TempDir
    Path workspace;

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void completesEvidenceDrivenActionLoopAndWritesFinalResponse() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 3);
        expectDecision(clickDecision());
        expectDecision(doneDecision("evidence-2", "submitted"));

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables())
                .containsEntry(GuiAgentNode.URL_KEY, "https://page.test/start")
                .containsEntry(GuiAgentNode.GOAL_KEY, "提交测试表单")
                .containsEntry(GuiAgentNode.SUMMARY_KEY, "submitted")
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "submitted")
                .containsEntry(GuiAgentNode.FINAL_URL_KEY, "https://page.test/final")
                .containsEntry(GuiAgentNode.DOM_KEY, "<div>submitted</div>")
                .containsEntry(GuiAgentNode.MODEL_KEY, "vision-model")
                .doesNotContainKey(GuiAgentNode.ERROR_KEY);
        assertThat(result.variables().get(GuiAgentNode.SCREENSHOT_DATA_URL_KEY))
                .startsWith("data:image/png;base64,");
        JsonNode evidence = objectMapper.readTree(result.variables().get(GuiAgentNode.EVIDENCE_KEY));
        assertThat(evidence).hasSize(3);
        assertThat(evidence.get(0).path("id").textValue()).isEqualTo("evidence-0");
        assertThat(evidence.get(1).path("id").textValue()).isEqualTo("evidence-1");
        assertThat(evidence.get(2).path("id").textValue()).isEqualTo("evidence-2");
        assertThat(evidence.get(2).path("selector").textValue()).isEqualTo("page");
        assertThat(evidence.get(0).path("dom").textValue()).isEqualTo("<div>ready</div>");
        assertThat(evidence.get(0).path("visibleText").textValue()).isEqualTo("ready");
        assertThat(evidence.get(1).path("dom").textValue()).isEqualTo("<div>submitted</div>");
        assertThat(evidence.get(1).path("visibleText").textValue()).isEqualTo("submitted");
        assertThat(evidence.get(2).path("visibleText").textValue()).isEqualTo("submitted");
        assertThat(evidence).allSatisfy(item ->
                assertThat(item.path("screenshotDataUrl").textValue())
                        .startsWith("data:image/png;base64,"));
        JsonNode actions = objectMapper.readTree(result.variables().get(GuiAgentNode.ACTIONS_KEY));
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).path("action").textValue()).isEqualTo("click");
        assertThat(actions.get(1).path("action").textValue()).isEqualTo("done");
        assertThat(result.trace()).containsExactly("gui");
        assertThat(browser.clickedSelector).isEqualTo("#submit");
        assertThat(browser.closed).isTrue();
        assertThat(runtime.audits).extracting(ToolAuditEvent::toolName).containsExactly(
                "browser.navigate", "browser.evidence", "browser.click",
                "browser.evidence", "browser.evidence");
        assertThat(runtime.events).extracting(HarnessEvent::eventType).contains(
                HarnessEventType.BEFORE_TOOL,
                HarnessEventType.AFTER_TOOL);
        server.verify();
        runtime.close();
    }

    @Test
    void letsTheModelRecoverFromAnActionToolFailure() throws Exception {
        TestBrowser browser = new TestBrowser();
        browser.clickFailure = new IllegalStateException("button detached");
        TestRuntime runtime = runtime(browser, 3);
        expectDecision(clickDecision());
        expectDecision(doneDecision("evidence-0", "ready"));

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables())
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "ready")
                .doesNotContainKey(GuiAgentNode.ERROR_KEY);
        JsonNode actions = objectMapper.readTree(result.variables().get(GuiAgentNode.ACTIONS_KEY));
        assertThat(actions.get(0).path("toolStatus").textValue()).isEqualTo("FAILED");
        assertThat(actions.get(0).path("toolError").textValue())
                .contains("button detached")
                .contains("at ");
        assertThat(runtime.audits).extracting(ToolAuditEvent::status)
                .contains(com.agent.core.tool.ToolResultStatus.FAILED);
        assertThat(browser.captureCount).isEqualTo(1);
        assertThat(browser.closed).isTrue();
        server.verify();
        runtime.close();
    }

    @Test
    void rejectsDoneWithoutCollectedEvidenceAndStoresFullStack() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 2);
        expectDecision(doneDecision("evidence-99", "错误完成"));

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables().get(GuiAgentNode.ERROR_KEY))
                .contains("java.lang.IllegalArgumentException")
                .contains("evidence-99")
                .contains("at ");
        assertThat(result.variables()).doesNotContainKey(PlannerNode.FINAL_RESPONSE_KEY);
        assertThat(result.trace()).containsExactly("gui");
        assertThat(browser.closed).isTrue();
        server.verify();
        runtime.close();
    }

    @Test
    void rejectsDoneWhenReferencedDomDoesNotContainSummary() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 1);
        expectDecision(doneDecision("evidence-0", "不存在的完成文本"));

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables().get(GuiAgentNode.ERROR_KEY))
                .contains("done.summary")
                .contains("最新页面证据可见文本")
                .contains("at ");
        assertThat(result.variables()).doesNotContainKey(PlannerNode.FINAL_RESPONSE_KEY);
        assertThat(browser.closed).isTrue();
        server.verify();
        runtime.close();
    }

    @Test
    void rejectsDoneUsingHiddenHtmlText() throws Exception {
        TestBrowser hiddenBrowser = new TestBrowser();
        hiddenBrowser.hiddenDomText = "hidden completion";
        TestRuntime hiddenRuntime = runtime(hiddenBrowser, 1);
        expectDecision(doneDecision("evidence-0", "hidden completion"));

        AgentState hiddenResult = hiddenRuntime.execute(initialState());

        assertThat(hiddenResult.variables().get(GuiAgentNode.ERROR_KEY))
                .contains("可见文本")
                .contains("at ");
        assertThat(hiddenResult.variables()).doesNotContainKey(PlannerNode.FINAL_RESPONSE_KEY);
        server.verify();
        hiddenRuntime.close();
    }

    @Test
    void rejectsDoneUsingHistoricalEvidenceAfterSuccessfulAction() throws Exception {
        TestBrowser historicalBrowser = new TestBrowser();
        TestRuntime historicalRuntime = runtime(historicalBrowser, 2);
        expectDecision(clickDecision());
        expectDecision(doneDecision("evidence-0", "ready"));

        AgentState historicalResult = historicalRuntime.execute(initialState());

        assertThat(historicalResult.variables().get(GuiAgentNode.ERROR_KEY))
                .contains("最新页面证据")
                .contains("at ");
        assertThat(historicalResult.variables()).doesNotContainKey(PlannerNode.FINAL_RESPONSE_KEY);
        server.verify();
        historicalRuntime.close();
    }

    @Test
    void stopsAtMaxStepsAndClosesTheRunSession() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 1);
        expectDecision(clickDecision());

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables().get(GuiAgentNode.ERROR_KEY))
                .contains("java.lang.IllegalStateException")
                .contains("maxSteps")
                .contains("at ");
        assertThat(result.variables().get(GuiAgentNode.STEP_KEY)).isEqualTo("1");
        assertThat(browser.closed).isTrue();
        server.verify();
        runtime.close();
    }

    @Test
    void returnsGuiErrorWhenCriticalHarnessHookRejectsBrowserTool() throws Exception {
        TestBrowser browser = new TestBrowser();
        HarnessHook critical = new HarnessHook() {
            @Override
            public void onEvent(HarnessEvent event) {
                if (event.eventType() == HarnessEventType.BEFORE_TOOL) {
                    throw new IllegalStateException("critical gui hook failed");
                }
            }

            @Override
            public boolean critical() {
                return true;
            }
        };
        TestRuntime runtime = runtime(browser, 2, new HarnessHookChain(List.of(critical)));

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables().get(GuiAgentNode.ERROR_KEY))
                .contains("HarnessHookException")
                .contains("critical gui hook failed")
                .contains("at ");
        assertThat(result.variables()).doesNotContainKey(PlannerNode.FINAL_RESPONSE_KEY);
        assertThat(browser.closed).isTrue();
        runtime.close();
    }

    @Test
    void rejectsMalformedModelJsonAndStoresTheRawResponse() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 2);
        expectDecision("""
                {"action":"done","selector":"","value":"","deltaY":0,
                 "evidenceSelector":"","reason":"完成","summary":"完成",
                 "evidenceRefs":["evidence-0"],"unknown":true}
                """);
        expectTextDecision("""
                {"action":"done","selector":"","value":"","deltaY":0,
                 "evidenceSelector":"","reason":"完成","summary":"完成",
                 "evidenceRefs":["evidence-0"],"unknown":true}
                """);

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables().get(GuiAgentNode.ERROR_KEY))
                .contains("UnrecognizedPropertyException")
                .contains("unknown")
                .contains("at ");
        assertThat(result.variables().get(GuiAgentNode.RESPONSE_KEY)).contains("unknown");
        assertThat(browser.closed).isTrue();
        server.verify();
        runtime.close();
    }

    @Test
    void rejectsWrongBrowserActionFunctionName() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 1);
        String response = responseJsonWithToolCalls(
                "other_action", List.of(doneDecision("evidence-0", "ready")));
        expectDecisionResponse(response);
        expectDecisionResponse(response);

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables().get(GuiAgentNode.ERROR_KEY))
                .contains("ToolCall name 必须为 browser_action")
                .contains("at ");
        assertThat(result.variables()).doesNotContainKey(PlannerNode.FINAL_RESPONSE_KEY);
        assertThat(browser.closed).isTrue();
        server.verify();
        runtime.close();
    }

    @Test
    void rejectsMultipleBrowserActionToolCalls() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 1);
        String done = doneDecision("evidence-0", "ready");
        String response = responseJsonWithToolCalls(
                "browser_action", List.of(done, done));
        expectDecisionResponse(response);
        expectDecisionResponse(response);

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables().get(GuiAgentNode.ERROR_KEY))
                .contains("必须只包含一个 ToolCall")
                .contains("at ");
        assertThat(result.variables()).doesNotContainKey(PlannerNode.FINAL_RESPONSE_KEY);
        assertThat(browser.closed).isTrue();
        server.verify();
        runtime.close();
    }

    @Test
    void fallsBackToDomTextWhenMultimodalRequestIsRejected() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 1);
        expectMultimodalFailure(HttpStatus.BAD_REQUEST);
        expectTextDecision(doneDecision("evidence-0", "ready"));

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables())
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "ready")
                .containsEntry(GuiAgentNode.SUMMARY_KEY, "ready")
                .doesNotContainKey(GuiAgentNode.ERROR_KEY);
        assertThat(browser.closed).isTrue();
        server.verify();
        runtime.close();
    }

    @Test
    void fallsBackToDomTextWhenMultimodalResponseViolatesActionProtocol() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 1);
        expectMultimodalTextResponse("images are not supported");
        expectTextDecision(doneDecision("evidence-0", "ready"));

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables())
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "ready")
                .doesNotContainKey(GuiAgentNode.ERROR_KEY);
        server.verify();
        runtime.close();
    }

    private TestRuntime runtime(TestBrowser browser, int maxSteps) {
        return runtime(browser, maxSteps, new HarnessHookChain(List.of()));
    }

    private TestRuntime runtime(
            TestBrowser browser,
            int maxSteps,
            HarnessHookChain harness) {
        List<ToolAuditEvent> audits = new CopyOnWriteArrayList<>();
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        BrowserSessionRegistry sessions = new BrowserSessionRegistry(() -> browser);
        DefaultToolRegistry tools = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(),
                new DefaultToolAuthorizer(),
                audits::add,
                objectMapper,
                System::nanoTime);
        tools.registerAll(BrowserToolDefinitions.definitions(sessions, objectMapper, TIMEOUT));
        GuiAgentNode node = new GuiAgentNode(
                sessions,
                modelRouter(),
                objectMapper,
                tools,
                TIMEOUT,
                maxSteps);
        List<HarnessHook> hooks = new ArrayList<>();
        hooks.add(events::add);
        hooks.addAll(harness.hooks());
        StateGraph graph = new StateGraph(
                BUDGET,
                InterruptPolicy.never(),
                new HarnessHookChain(hooks));
        graph.addNode("gui", node)
                .addEdge("gui", StateGraph.END)
                .setEntryPoint("gui");
        return new TestRuntime(graph, tools, sessions, audits, events);
    }

    private ModelRouter modelRouter() {
        if (client == null) {
            RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
            server = MockRestServiceServer.bindTo(builder).build();
            client = new LlmClient(builder.build(), objectMapper, CHAT_PATH);
        }
        ModelEndpoint endpoint = new ModelEndpoint(
                "vision-endpoint",
                "vision-model",
                client,
                CircuitBreaker.ofDefaults("gui-vision"));
        Map<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        for (TaskType taskType : TaskType.values()) {
            routes.put(taskType, List.of(endpoint));
        }
        return new ModelRouter(routes);
    }

    private void expectDecision(String decision) throws Exception {
        expectDecisionResponse(responseJson(decision));
    }

    private void expectDecisionResponse(String response) {
        server.expect(once(), requestTo(BASE_URL + CHAT_PATH))
                .andExpect(content().string(containsString("\"model\":\"vision-model\"")))
                .andExpect(content().string(containsString("提交测试表单")))
                .andExpect(content().string(containsString(
                        "\"name\":\"browser_action\"")))
                .andExpect(content().string(containsString(
                        "\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"browser_action\"}}")))
                .andExpect(content().string(containsString("\"strict\":true")))
                .andExpect(content().string(containsString("\"anyOf\"")))
                .andExpect(content().string(containsString("\"const\":\"click\"")))
                .andExpect(content().string(containsString("\"minItems\":1")))
                .andExpect(content().string(containsString(
                        "fill、click、scroll 的 summary 必须是空字符串")))
                .andExpect(content().string(containsString(
                        "reason 在所有动作中必须是非空字符串")))
                .andExpect(content().string(containsString(
                        "done 的 summary 必须逐字等于引用证据 DOM 中出现的可见文本")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void expectMultimodalFailure(HttpStatus status) {
        server.expect(once(), requestTo(BASE_URL + CHAT_PATH))
                .andExpect(content().string(containsString("\"image_url\"")))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"type\":\"upstream_error\"}}"));
    }

    private void expectMultimodalTextResponse(String text) throws Exception {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", "gui-text-response");
        response.put("object", "chat.completion");
        response.put("created", 1);
        response.put("model", "vision-model");
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message")
                .put("role", "assistant")
                .put("content", text);
        choice.put("finish_reason", "stop");
        server.expect(once(), requestTo(BASE_URL + CHAT_PATH))
                .andExpect(content().string(containsString("\"image_url\"")))
                .andRespond(withSuccess(
                        objectMapper.writeValueAsString(response),
                        MediaType.APPLICATION_JSON));
    }

    private void expectTextDecision(String decision) throws Exception {
        server.expect(once(), requestTo(BASE_URL + CHAT_PATH))
                .andExpect(content().string(containsString("\"model\":\"vision-model\"")))
                .andExpect(content().string(containsString("提交测试表单")))
                .andExpect(content().string(not(containsString("\"image_url\""))))
                .andExpect(content().string(containsString(
                        "\"name\":\"browser_action\"")))
                .andExpect(content().string(containsString(
                        "\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"browser_action\"}}")))
                .andRespond(withSuccess(responseJson(decision), MediaType.APPLICATION_JSON));
    }

    private String responseJson(String decision) throws Exception {
        return responseJsonWithToolCalls("browser_action", List.of(decision));
    }

    private String responseJsonWithToolCalls(
            String functionName,
            List<String> arguments) throws Exception {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", "gui-response");
        response.put("object", "chat.completion");
        response.put("created", 1L);
        response.put("model", "vision-model");
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message").put("role", "assistant");
        message.putNull("content");
        var toolCalls = message.putArray("tool_calls");
        for (int index = 0; index < arguments.size(); index++) {
            toolCalls.addObject()
                    .put("id", "browser-action-call-" + index)
                    .put("type", "function")
                    .putObject("function")
                    .put("name", functionName)
                    .put("arguments", arguments.get(index));
        }
        choice.put("finish_reason", "tool_calls");
        return objectMapper.writeValueAsString(response);
    }

    private AgentState initialState() {
        return AgentState.empty()
                .withVariable(ReviewerNode.URL_KEY, "https://page.test/start")
                .withVariable(PlannerNode.TASK_KEY, "提交测试表单")
                .withVariable(PlannerNode.USER_ID_KEY, "user-a")
                .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString());
    }

    private String clickDecision() throws Exception {
        return decision("click", "#submit", "", 0, "#result", "提交表单", "", List.of());
    }

    private String doneDecision(String evidenceRef, String summary) throws Exception {
        return decision("done", "", "", 0, "", "目标已完成", summary, List.of(evidenceRef));
    }

    private String decision(
            String action,
            String selector,
            String value,
            int deltaY,
            String evidenceSelector,
            String reason,
            String summary,
            List<String> evidenceRefs) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("action", action)
                .put("selector", selector)
                .put("value", value)
                .put("deltaY", deltaY)
                .put("evidenceSelector", evidenceSelector)
                .put("reason", reason)
                .put("summary", summary)
                .set("evidenceRefs", objectMapper.valueToTree(evidenceRefs)));
    }

    private final class TestRuntime implements AutoCloseable {

        private final StateGraph graph;
        private final DefaultToolRegistry tools;
        private final BrowserSessionRegistry sessions;
        private final List<ToolAuditEvent> audits;
        private final List<HarnessEvent> events;

        private TestRuntime(
                StateGraph graph,
                DefaultToolRegistry tools,
                BrowserSessionRegistry sessions,
                List<ToolAuditEvent> audits,
                List<HarnessEvent> events) {
            this.graph = graph;
            this.tools = tools;
            this.sessions = sessions;
            this.audits = audits;
            this.events = events;
        }

        private AgentState execute(AgentState state) {
            return graph.execute(state);
        }

        @Override
        public void close() {
            graph.close();
            tools.close();
            sessions.close();
        }
    }

    private static final class TestBrowser implements BrowserAutomation {

        private int captureCount;
        private boolean submitted;
        private boolean closed;
        private String clickedSelector;
        private RuntimeException clickFailure;
        private String hiddenDomText = "";

        @Override
        public CompletableFuture<NavigationResult> navigate(URI url, Duration timeout) {
            return CompletableFuture.completedFuture(new NavigationResult(
                    url,
                    URI.create("https://page.test/final"),
                    OptionalInt.of(200)));
        }

        @Override
        public CompletableFuture<Void> click(String selector, Duration timeout) {
            clickedSelector = selector;
            if (clickFailure != null) {
                return CompletableFuture.failedFuture(clickFailure);
            }
            submitted = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> fill(
                String selector,
                String value,
                Duration timeout) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> scroll(int deltaY, Duration timeout) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<BrowserEvidence> capture(
                BrowserEvidenceSelector selector,
                Duration timeout) {
            captureCount++;
            String dom = submitted ? "<div>submitted</div>" : "<div>ready</div>";
            if (!hiddenDomText.isEmpty()) {
                dom = "<script>" + hiddenDomText + "</script>" + dom;
            }
            String visibleText = submitted ? "submitted" : "ready";
            return CompletableFuture.completedFuture(new BrowserEvidence(
                    URI.create("https://page.test/final"),
                    selector.selector(),
                    dom,
                    visibleText,
                    new BrowserScreenshot(new byte[] {(byte) captureCount}, "image/png")));
        }

        @Override
        public CompletableFuture<String> extractDom() {
            return CompletableFuture.completedFuture("<html></html>");
        }

        @Override
        public CompletableFuture<String> extractDom(Duration timeout) {
            return CompletableFuture.completedFuture("<html></html>");
        }

        @Override
        public CompletableFuture<BrowserScreenshot> screenshot(Duration timeout) {
            return CompletableFuture.completedFuture(
                    new BrowserScreenshot(new byte[] {1}, "image/png"));
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
