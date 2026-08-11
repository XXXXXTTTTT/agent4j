package com.agent.eval;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.StateGraph;
import com.agent.core.gui.BrowserSessionRegistry;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.OpenAiEndpoint;
import com.agent.core.llm.TaskType;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.GuiAgentNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.tool.DefaultToolAuthorizer;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.JacksonToolSchemaValidator;
import com.agent.core.tool.ToolAuditEvent;
import com.agent.sandbox.browser.PlaywrightBrowserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用配置的真实模型验证 GUI Agent 的页面动作与证据闭环。 */
@Tag("edd")
class LiveGuiAgentWorkflowEddTest {

    private static final Duration BROWSER_TIMEOUT = Duration.ofSeconds(45);
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @Test
    void closesEveryLiveResourceWhenOneCleanupFails() {
        boolean[] closed = new boolean[3];
        AutoCloseable first = () -> {
            closed[0] = true;
            throw new IllegalStateException("tools close failed");
        };
        AutoCloseable second = () -> closed[1] = true;
        AutoCloseable third = () -> closed[2] = true;

        assertThatThrownBy(() -> closeLiveResources(first, second, third))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tools close failed");
        assertThat(closed).containsExactly(true, true, true);
    }

    @Test
    void executesGuiAgentAgainstConfiguredModelAndWritesLiveReport() throws Exception {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_LLM_ENABLED", "false"))) {
            Assumptions.assumeTrue(false, "AGENT_LLM_ENABLED 未开启，跳过 Live GUI EDD");
        }
        Configuration configuration = Configuration.fromEnvironment();
        OpenAiEndpoint endpoint = OpenAiEndpoint.resolve(
                configuration.baseUrl(), configuration.chatCompletionsPath());
        requireLaunchableChromium();
        HttpServer pageServer = startPageServer();
        URI pageUri = URI.create("http://127.0.0.1:"
                + pageServer.getAddress().getPort() + "/form");
        List<ToolAuditEvent> audits = new CopyOnWriteArrayList<>();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BrowserSessionRegistry sessions = null;
        DefaultToolRegistry tools = null;
        LlmClient client = null;
        Instant started = Instant.now();
        LiveResult report;
        String diagnostic = "";
        try {
            sessions = new BrowserSessionRegistry(PlaywrightBrowserService::new);
            tools = new DefaultToolRegistry(
                    new JacksonToolSchemaValidator(),
                    new DefaultToolAuthorizer(),
                    audits::add,
                    mapper,
                    System::nanoTime);
            client = new LlmClient(
                    org.springframework.web.client.RestClient.builder()
                            .baseUrl(endpoint.transportBaseUrl())
                            .defaultHeader(org.springframework.http.HttpHeaders.AUTHORIZATION,
                                    "Bearer " + configuration.apiKey())
                            .build(),
                    mapper,
                    endpoint.requestPath(),
                    endpoint.requestUrl());
            tools.registerAll(com.agent.core.tool.builtin.BrowserToolDefinitions.definitions(
                    sessions, mapper, BROWSER_TIMEOUT));
            ModelRouter router = router(configuration, client);
            GuiAgentNode gui = new GuiAgentNode(
                    sessions, router, mapper, tools, BROWSER_TIMEOUT, 5);
            try (StateGraph graph = new StateGraph(8)
                     .addNode("gui", gui)
                     .addEdge("gui", StateGraph.END)
                     .setEntryPoint("gui")) {
            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(ReviewerNode.URL_KEY, pageUri.toString())
                    .withVariable(PlannerNode.TASK_KEY,
                            "在页面表单中把 Name 填写为 Agent4J，点击 Submit，确认结果显示 submitted: Agent4J")
                    .withVariable(PlannerNode.USER_ID_KEY, "live-edd-user")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY,
                            Path.of(".").toAbsolutePath().normalize().toString()));
            report = report(configuration, result, audits.size(),
                    Duration.between(started, Instant.now()).toMillis());
            diagnostic = diagnostic(result);
            }
        } catch (Throwable failure) {
            report = new LiveResult(
                    "gui-agent.live-form-submit", "LIVE", configuration.baseUrl(),
                    configuration.visionModel(), "FAILED", 0, audits.size(), "", "", "",
                    false, failure.getClass().getSimpleName());
            diagnostic = failure.toString();
        } finally {
            closeLiveResources(
                    tools,
                    client,
                    sessions,
                    () -> pageServer.stop(0));
        }
        writeReport(mapper, report);
        assertThat(report.passed())
                .as("Live GUI EDD 失败，错误类型=" + report.errorType()
                        + "，诊断=" + diagnostic)
                .isTrue();
    }

    private static void closeLiveResources(AutoCloseable... resources) {
        RuntimeException failure = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable exception) {
                RuntimeException current = exception instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("Live EDD 资源清理失败", exception);
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private ModelRouter router(Configuration configuration, LlmClient client) {
        ModelEndpoint primary = new ModelEndpoint(
                "live-vision-primary",
                configuration.visionModel(),
                client,
                CircuitBreaker.ofDefaults("live-gui-vision"));
        ModelEndpoint fallback = new ModelEndpoint(
                "live-vision-fallback",
                configuration.fallbackModel(),
                client,
                CircuitBreaker.ofDefaults("live-gui-fallback"));
        EnumMap<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        for (TaskType taskType : TaskType.values()) {
            routes.put(taskType, List.of(primary, fallback));
        }
        return new ModelRouter(routes);
    }

    private LiveResult report(
            Configuration configuration,
            AgentState result,
            int toolCalls,
            long durationMs) throws IOException {
        JsonNode evidence = new ObjectMapper().readTree(
                result.variables().getOrDefault(GuiAgentNode.EVIDENCE_KEY, "[]"));
        JsonNode finalEvidence = evidence.isArray() && !evidence.isEmpty()
                ? evidence.get(evidence.size() - 1) : null;
        boolean passed = !result.variables().containsKey(GuiAgentNode.ERROR_KEY)
                && result.variables().containsKey(PlannerNode.FINAL_RESPONSE_KEY)
                && finalEvidence != null
                && finalEvidence.path("dom").textValue() != null
                && finalEvidence.path("dom").textValue().contains("submitted: Agent4J")
                && screenshotBytes(
                        result.variables().get(GuiAgentNode.SCREENSHOT_DATA_URL_KEY)).length > 0;
        return new LiveResult(
                "gui-agent.live-form-submit", "LIVE", configuration.baseUrl(),
                configuration.visionModel(), passed ? "COMPLETED" : "FAILED",
                Integer.parseInt(result.variables().getOrDefault(GuiAgentNode.STEP_KEY, "0")),
                toolCalls,
                finalEvidence == null ? "" : finalEvidence.path("finalUrl").textValue(),
                finalEvidence == null ? "" : finalEvidence.path("domSha256").textValue(),
                finalEvidence == null ? "" : finalEvidence.path("screenshotSha256").textValue(),
                passed,
                errorType(result.variables().getOrDefault(GuiAgentNode.ERROR_KEY, "")));
    }

    private void writeReport(ObjectMapper mapper, LiveResult report) throws IOException {
        Path path = Path.of("target", "edd", "live-gui-agent-edd.json");
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),
                Map.of("generatedAt", Instant.now(), "scenario", report));
    }

    private byte[] screenshotBytes(String dataUrl) {
        String prefix = "data:image/png;base64,";
        if (dataUrl == null || !dataUrl.startsWith(prefix)) {
            return new byte[0];
        }
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(prefix.length()));
        assertThat(bytes).startsWith(PNG_SIGNATURE);
        return bytes;
    }

    private String errorType(String errorStack) {
        if (errorStack == null || errorStack.isBlank()) {
            return "";
        }
        int lineEnd = errorStack.indexOf(System.lineSeparator());
        String firstLine = lineEnd < 0 ? errorStack : errorStack.substring(0, lineEnd);
        int messageStart = firstLine.indexOf(':');
        return messageStart < 0 ? firstLine : firstLine.substring(0, messageStart);
    }

    private String diagnostic(AgentState result) {
        String error = result.variables().getOrDefault(GuiAgentNode.ERROR_KEY, "");
        String response = result.variables().getOrDefault(GuiAgentNode.RESPONSE_KEY, "");
        String actions = result.variables().getOrDefault(GuiAgentNode.ACTIONS_KEY, "");
        String dom = result.variables().getOrDefault(GuiAgentNode.DOM_KEY, "");
        String value = "error=" + error + "; response=" + response
                + "; actions=" + actions + "; dom=" + dom;
        return value.length() <= 4_000 ? value : value.substring(0, 4_000);
    }

    private HttpServer startPageServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/form", this::serveForm);
        server.start();
        return server;
    }

    private void serveForm(HttpExchange exchange) throws IOException {
        byte[] response = """
                <!doctype html><html><body>
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

    private void requireLaunchableChromium() {
        Playwright playwright = null;
        Browser browser = null;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
        } catch (PlaywrightException exception) {
            Assumptions.assumeTrue(false,
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

    private record Configuration(
            String baseUrl,
            String apiKey,
            String chatCompletionsPath,
            String visionModel,
            String fallbackModel) {

        private static Configuration fromEnvironment() {
            return new Configuration(
                    required("AGENT_LLM_BASE_URL"),
                    required("AGENT_LLM_API_KEY"),
                    valueOrDefault("AGENT_LLM_CHAT_COMPLETIONS_PATH", "/v1/chat/completions"),
                    required("AGENT_LLM_VISION_MODEL"),
                    required("AGENT_LLM_FALLBACK_MODEL"));
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " 未配置");
            }
            return value.trim();
        }

        private static String valueOrDefault(String name, String defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }
    }

    private record LiveResult(
            String taskId,
            String mode,
            String endpoint,
            String model,
            String status,
            int steps,
            int toolCalls,
            String finalUrl,
            String domSha256,
            String screenshotSha256,
            boolean passed,
            String errorType) {
    }
}
