package com.agent.eval;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.StateGraph;
import com.agent.core.gui.BrowserSessionRegistry;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.GuiAgentNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.JacksonToolSchemaValidator;
import com.agent.core.tool.ToolAuditEvent;
import com.agent.core.tool.builtin.BrowserToolDefinitions;
import com.agent.sandbox.browser.PlaywrightBrowserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 使用真实 Chromium 和本地页面验证 GUI Agent 证据闭环。 */
@Tag("edd")
class GuiAgentWorkflowEddTest {

    private static final String BASE_URL = "https://gui-agent-edd.test";
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final Set<String> REPORT_FIELDS = Set.of(
            "taskId",
            "status",
            "steps",
            "toolCalls",
            "evidenceRefs",
            "finalUrl",
            "domSha256",
            "screenshotSha256",
            "passed");

    @TempDir
    Path workspace;

    @Test
    void fillsAndSubmitsRealPageWithEvidenceReferencedCompletion() throws Exception {
        requireLaunchableChromium();
        HttpServer pageServer = startPageServer();
        URI pageUri = URI.create("http://127.0.0.1:"
                + pageServer.getAddress().getPort() + "/form");
        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer modelServer = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(builder.build(), mapper, COMPLETIONS_PATH);
        ModelRouter router = router(client);
        AtomicInteger responseIndex = new AtomicInteger();
        modelServer.expect(times(3), requestTo(BASE_URL + COMPLETIONS_PATH))
                .andRespond(request -> {
                    String requestBody = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(requestBody).contains("在表单中输入 Agent4J 并提交");
                    return withSuccess(
                            completion(mapper, decision(mapper, responseIndex.getAndIncrement())),
                            MediaType.APPLICATION_JSON).createResponse(request);
                });

        List<ToolAuditEvent> audits = new CopyOnWriteArrayList<>();
        BrowserSessionRegistry sessions = new BrowserSessionRegistry(
                PlaywrightBrowserService::new);
        DefaultToolRegistry tools = new DefaultToolRegistry(
                new JacksonToolSchemaValidator(),
                new DefaultToolAuthorizer(),
                audits::add,
                mapper,
                System::nanoTime);
        tools.registerAll(BrowserToolDefinitions.definitions(sessions, mapper, TIMEOUT));
        GuiAgentNode gui = new GuiAgentNode(
                sessions, router, mapper, tools, TIMEOUT, 5);

        try (client;
             tools;
             sessions;
             StateGraph graph = new StateGraph(3)
                     .addNode("gui", gui)
                     .addEdge("gui", StateGraph.END)
                     .setEntryPoint("gui")) {
            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(ReviewerNode.URL_KEY, pageUri.toString())
                    .withVariable(PlannerNode.TASK_KEY,
                            "在表单中输入 Agent4J 并提交")
                    .withVariable(PlannerNode.USER_ID_KEY, "edd-user")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

            assertThat(result.trace()).containsExactly("gui");
            assertThat(result.variables())
                    .containsEntry(PlannerNode.FINAL_RESPONSE_KEY,
                            "页面已显示 submitted: Agent4J")
                    .containsEntry(GuiAgentNode.DOM_KEY,
                            "<div id=\"result\">submitted: Agent4J</div>")
                    .containsEntry(GuiAgentNode.FINAL_URL_KEY, pageUri.toString())
                    .doesNotContainKeys(
                            GuiAgentNode.ERROR_KEY,
                            CoderNode.UPDATED_FILES_KEY,
                            OpsNode.COMMAND_KEY,
                            ReviewerNode.APPROVED_KEY);
            JsonNode evidence = mapper.readTree(
                    result.variables().get(GuiAgentNode.EVIDENCE_KEY));
            assertThat(evidence).hasSize(3);
            assertThat(evidence.get(2).path("id").textValue()).isEqualTo("evidence-2");
            assertThat(evidence.get(2).path("dom").textValue())
                    .contains("submitted: Agent4J");
            byte[] screenshot = screenshotBytes(
                    evidence.get(2).path("screenshotDataUrl").textValue());
            assertThat(screenshot).startsWith(PNG_SIGNATURE);
            assertThat(audits).extracting(ToolAuditEvent::toolName).containsExactly(
                    "browser.navigate",
                    "browser.evidence",
                    "browser.fill",
                    "browser.evidence",
                    "browser.click",
                    "browser.evidence");
            writeAndVerifyReport(mapper, result, audits.size(), evidence.get(2));
        } finally {
            pageServer.stop(0);
        }
        modelServer.verify();
    }

    private HttpServer startPageServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/form", this::serveForm);
        server.start();
        return server;
    }

    private void serveForm(HttpExchange exchange) throws IOException {
        byte[] response = """
                <!doctype html>
                <html><body>
                <label for="name">Name</label>
                <input id="name" value="">
                <button id="submit" onclick="document.querySelector('#result').textContent='submitted: ' + document.querySelector('#name').value">Submit</button>
                <div id="result">not submitted</div>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (exchange; var body = exchange.getResponseBody()) {
            body.write(response);
        }
    }

    private ModelRouter router(LlmClient client) {
        ModelEndpoint endpoint = new ModelEndpoint(
                "vision",
                "vision-model",
                client,
                CircuitBreaker.ofDefaults("gui-agent-edd-vision"));
        Map<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        for (TaskType taskType : TaskType.values()) {
            routes.put(taskType, List.of(endpoint));
        }
        return new ModelRouter(routes);
    }

    private String decision(ObjectMapper mapper, int index) throws IOException {
        return switch (index) {
            case 0 -> action(mapper, "fill", "#name", "Agent4J", 0,
                    "#name", "填写表单名称", "", List.of());
            case 1 -> action(mapper, "click", "#submit", "", 0,
                    "#result", "提交表单", "", List.of());
            case 2 -> action(mapper, "done", "", "", 0,
                    "", "页面结果已确认", "页面已显示 submitted: Agent4J",
                    List.of("evidence-2"));
            default -> throw new IllegalStateException("超出确定性视觉响应数量: " + index);
        };
    }

    private String action(
            ObjectMapper mapper,
            String action,
            String selector,
            String value,
            int deltaY,
            String evidenceSelector,
            String reason,
            String summary,
            List<String> evidenceRefs) throws IOException {
        return mapper.writeValueAsString(mapper.createObjectNode()
                .put("action", action)
                .put("selector", selector)
                .put("value", value)
                .put("deltaY", deltaY)
                .put("evidenceSelector", evidenceSelector)
                .put("reason", reason)
                .put("summary", summary)
                .set("evidenceRefs", mapper.valueToTree(evidenceRefs)));
    }

    private String completion(ObjectMapper mapper, String decision) throws IOException {
        ObjectNode response = mapper.createObjectNode();
        response.put("id", "gui-agent-edd-response");
        response.put("object", "chat.completion");
        response.put("created", 1L);
        response.put("model", "vision-model");
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message")
                .put("role", "assistant")
                .put("content", decision);
        choice.put("finish_reason", "stop");
        return mapper.writeValueAsString(response);
    }

    private byte[] screenshotBytes(String dataUrl) {
        String prefix = "data:image/png;base64,";
        assertThat(dataUrl).startsWith(prefix);
        return Base64.getDecoder().decode(dataUrl.substring(prefix.length()));
    }

    private void writeAndVerifyReport(
            ObjectMapper mapper,
            AgentState result,
            int toolCalls,
            JsonNode finalEvidence) throws Exception {
        JsonNode actions = mapper.readTree(result.variables().get(GuiAgentNode.ACTIONS_KEY));
        List<String> evidenceRefs = new ArrayList<>();
        actions.get(actions.size() - 1).path("evidenceRefs")
                .forEach(reference -> evidenceRefs.add(reference.textValue()));
        EddResult scenario = new EddResult(
                "gui-agent.form-submit",
                "COMPLETED",
                Integer.parseInt(result.variables().get(GuiAgentNode.STEP_KEY)),
                toolCalls,
                evidenceRefs,
                result.variables().get(GuiAgentNode.FINAL_URL_KEY),
                finalEvidence.path("domSha256").textValue(),
                finalEvidence.path("screenshotSha256").textValue(),
                true);
        Path report = Path.of("target", "edd", "gui-agent-workflow-edd.json");
        Files.createDirectories(report.getParent());
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(report.toFile(), Map.of("scenarios", List.of(scenario)));
        JsonNode written = mapper.readTree(report.toFile()).path("scenarios").get(0);
        Set<String> fields = new LinkedHashSet<>();
        written.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrderElementsOf(REPORT_FIELDS);
        assertThat(written.path("steps").intValue()).isEqualTo(3);
        assertThat(written.path("toolCalls").intValue()).isEqualTo(6);
        assertThat(written.path("evidenceRefs").get(0).textValue())
                .isEqualTo("evidence-2");
        assertThat(written.path("domSha256").textValue()).matches("[0-9a-f]{64}");
        assertThat(written.path("screenshotSha256").textValue()).matches("[0-9a-f]{64}");
        assertThat(written.path("passed").booleanValue()).isTrue();
    }

    private void requireLaunchableChromium() {
        Playwright playwright = null;
        Browser browser = null;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
        } catch (PlaywrightException exception) {
            assumeTrue(false,
                    "当前环境无法启动 Playwright Chromium: " + exception.getMessage());
        } finally {
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
        }
    }

    private record EddResult(
            String taskId,
            String status,
            int steps,
            int toolCalls,
            List<String> evidenceRefs,
            String finalUrl,
            String domSha256,
            String screenshotSha256,
            boolean passed) {
    }
}
