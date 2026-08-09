package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.StateGraph;
import com.agent.core.gui.BrowserSessionRegistry;
import com.agent.core.harness.HarnessEvent;
import com.agent.core.harness.HarnessEventType;
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
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
        expectDecision(doneDecision("evidence-1", "表单提交完成"));

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables())
                .containsEntry(GuiAgentNode.URL_KEY, "https://page.test/start")
                .containsEntry(GuiAgentNode.GOAL_KEY, "提交测试表单")
                .containsEntry(GuiAgentNode.SUMMARY_KEY, "表单提交完成")
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "表单提交完成")
                .containsEntry(GuiAgentNode.FINAL_URL_KEY, "https://page.test/final")
                .containsEntry(GuiAgentNode.DOM_KEY, "<div>submitted</div>")
                .containsEntry(GuiAgentNode.MODEL_KEY, "vision-model")
                .doesNotContainKey(GuiAgentNode.ERROR_KEY);
        assertThat(result.variables().get(GuiAgentNode.SCREENSHOT_DATA_URL_KEY))
                .startsWith("data:image/png;base64,");
        JsonNode evidence = objectMapper.readTree(result.variables().get(GuiAgentNode.EVIDENCE_KEY));
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).path("id").textValue()).isEqualTo("evidence-0");
        assertThat(evidence.get(1).path("id").textValue()).isEqualTo("evidence-1");
        JsonNode actions = objectMapper.readTree(result.variables().get(GuiAgentNode.ACTIONS_KEY));
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).path("action").textValue()).isEqualTo("click");
        assertThat(actions.get(1).path("action").textValue()).isEqualTo("done");
        assertThat(result.trace()).containsExactly("gui");
        assertThat(browser.clickedSelector).isEqualTo("#submit");
        assertThat(browser.closed).isTrue();
        assertThat(runtime.audits).extracting(ToolAuditEvent::toolName).containsExactly(
                "browser.navigate", "browser.evidence", "browser.click", "browser.evidence");
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
        expectDecision(doneDecision("evidence-0", "保留失败前证据"));

        AgentState result = runtime.execute(initialState());

        assertThat(result.variables())
                .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "保留失败前证据")
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
    void rejectsMalformedModelJsonAndStoresTheRawResponse() throws Exception {
        TestBrowser browser = new TestBrowser();
        TestRuntime runtime = runtime(browser, 2);
        expectDecision("""
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

    private TestRuntime runtime(TestBrowser browser, int maxSteps) {
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
        StateGraph graph = new StateGraph(
                BUDGET,
                InterruptPolicy.never(),
                new HarnessHookChain(List.of(events::add)));
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
        server.expect(once(), requestTo(BASE_URL + CHAT_PATH))
                .andExpect(content().string(containsString("\"model\":\"vision-model\"")))
                .andExpect(content().string(containsString("提交测试表单")))
                .andRespond(withSuccess(responseJson(decision), MediaType.APPLICATION_JSON));
    }

    private String responseJson(String decision) throws Exception {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", "gui-response");
        response.put("object", "chat.completion");
        response.put("created", 1L);
        response.put("model", "vision-model");
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message")
                .put("role", "assistant")
                .put("content", decision);
        choice.put("finish_reason", "stop");
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
            return CompletableFuture.completedFuture(new BrowserEvidence(
                    URI.create("https://page.test/final"),
                    selector.selector(),
                    dom,
                    new BrowserScreenshot(new byte[] {(byte) captureCount}, "image/png")));
        }

        @Override
        public CompletableFuture<String> extractDom() {
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
