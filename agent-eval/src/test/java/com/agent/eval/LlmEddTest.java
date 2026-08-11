package com.agent.eval;

import com.agent.core.engine.AgentState;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.OpenAiEndpoint;
import com.agent.core.llm.TaskType;
import com.agent.core.memory.MemoryContext;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.rag.pipeline.ModelHypotheticalDocumentGenerator;
import com.agent.rag.pipeline.ModelQueryRewriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 OpenAI 兼容端点执行预设对话 EDD。默认不启用，避免普通构建依赖外部服务。 */
@Tag("edd")
class LlmEddTest {

    @TempDir
    Path workspace;

    @Test
    void evaluatesConversationRoutesAndWritesAuditReport() throws Exception {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_LLM_ENABLED", "false"))) {
            Assumptions.assumeTrue(false, "AGENT_LLM_ENABLED 未开启，跳过真实 LLM EDD");
        }
        EddConfiguration configuration = EddConfiguration.fromEnvironment();
        OpenAiEndpoint endpoint = OpenAiEndpoint.resolve(
                configuration.baseUrl(), configuration.chatCompletionsPath());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RestClient restClient = RestClient.builder()
                .baseUrl(endpoint.transportBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiKey())
                .build();
        try (LlmClient client = new LlmClient(
                restClient,
                objectMapper,
                endpoint.requestPath(),
                endpoint.requestUrl())) {
            AtomicInteger modelCallAttempts = new AtomicInteger();
            ModelRouter router = router(configuration, client, modelCallAttempts);
            List<EddScenario> scenarios = List.of(
                    new EddScenario("model.identity", "你是什么模型", false,
                            response -> !response.isBlank()),
                    new EddScenario("travel.without.car",
                            "我在江西新余高新区，没有车只有电瓶车，请规划明天一日游", false,
                            response -> response.contains("新余") || response.contains("电瓶车")),
                    new EddScenario("weather.followup", "按天气规划", false,
                            List.of(
                                    ChatMessage.user("我在江西新余高新区，没有车只有电瓶车"),
                                    ChatMessage.assistant("可以围绕仙女湖和仰天岗安排一日游。")),
                            response -> !response.isBlank()),
                    new EddScenario("code.intent", "请修改 src/main/App.java 并运行测试", true,
                            response -> !response.isBlank()),
                    new EddScenario(
                            "memory.user-preference",
                            "请按我的项目偏好修改 Java 代码并给出验证命令",
                            true,
                            new MemoryContext(
                                    "用户编码偏好：构建和验证统一使用 Maven，命令必须包含 mvn。",
                                    1),
                            response -> response.contains("mvn") || response.contains("Maven")),
                    new EddScenario(
                            "memory.bad-case",
                            "请修复当前代码问题并给出测试计划",
                            true,
                            new MemoryContext(
                                    "历史 Bad Case：禁止全量覆盖文件，必须使用 Unified Diff，并运行测试。",
                                    1),
                            response -> response.contains("Diff") || response.contains("测试")));
            List<EddScenarioResult> results = scenarios.stream()
                    .map(scenario -> executeScenario(scenario, router))
                    .toList();
            writeReport(configuration, results, modelCallAttempts.get(), objectMapper);
            assertThat(modelCallAttempts.get())
                    .as("真实 LLM EDD 必须至少发起一次模型调用")
                    .isGreaterThan(0);
            assertThat(results).allSatisfy(result -> assertThat(result.passed())
                    .as(result.id() + " EDD 失败: " + result.error())
                    .isTrue());
        }
    }

    @Test
    void evaluatesRagEnhancerProtocolsWhenEnabled() throws Exception {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_LLM_ENABLED", "false"))) {
            Assumptions.assumeTrue(false, "AGENT_LLM_ENABLED 未开启，跳过真实 RAG 模型 EDD");
        }
        EddConfiguration configuration = EddConfiguration.fromEnvironment();
        OpenAiEndpoint endpoint = OpenAiEndpoint.resolve(
                configuration.baseUrl(), configuration.chatCompletionsPath());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RestClient restClient = RestClient.builder()
                .baseUrl(endpoint.transportBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiKey())
                .build();
        List<RagEnhancerResult> results;
        try (LlmClient client = new LlmClient(
                restClient,
                objectMapper,
                endpoint.requestPath(),
                endpoint.requestUrl())) {
            AtomicInteger modelCallAttempts = new AtomicInteger();
            ModelRouter router = router(configuration, client, modelCallAttempts);
            results = List.of(
                    runQueryRewrite(router, objectMapper),
                    runHyde(router, objectMapper));
        }
        Path directory = Path.of("target", "edd");
        Files.createDirectories(directory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve("llm-rag-enhancers-" + Instant.now().toEpochMilli() + ".json").toFile(),
                new RagEnhancerReport(configuration.baseUrl(), Instant.now(), results));
        assertThat(results).allSatisfy(result -> assertThat(result.passed())
                .as(result.taskId() + " RAG 模型 EDD 失败: " + result.error())
                .isTrue());
    }

    private RagEnhancerResult runQueryRewrite(
            ModelRouter router, ObjectMapper objectMapper) {
        try {
            List<String> rewrites = new ModelQueryRewriter(router, objectMapper)
                    .rewrite("如何定位用户认证失败的调用链", 2);
            boolean passed = rewrites.size() <= 2
                    && rewrites.stream().allMatch(item -> item != null && !item.isBlank());
            return new RagEnhancerResult(
                    "rag.model-query-rewrite", passed, rewrites.toString(), "");
        } catch (Throwable throwable) {
            return new RagEnhancerResult(
                    "rag.model-query-rewrite", false, "", stackTrace(throwable));
        }
    }

    private RagEnhancerResult runHyde(
            ModelRouter router, ObjectMapper objectMapper) {
        try {
            String document = new ModelHypotheticalDocumentGenerator(router, objectMapper)
                    .generate("如何定位用户认证失败的调用链");
            return new RagEnhancerResult(
                    "rag.model-hyde", !document.isBlank(), responsePreview(document), "");
        } catch (Throwable throwable) {
            return new RagEnhancerResult(
                    "rag.model-hyde", false, "", stackTrace(throwable));
        }
    }

    private EddScenarioResult executeScenario(EddScenario scenario, ModelRouter router) {
        Instant started = Instant.now();
        try {
            PlannerNode node = new PlannerNode(
                    router,
                    ignored -> scenario.memoryContext(),
                    5);
            AgentState state = new AgentState(scenario.history(), Map.of(PlannerNode.TASK_KEY,
                    scenario.prompt()), List.of())
                    .withVariable(PlannerNode.TASK_KEY, scenario.prompt());
            if (scenario.expectedAgent()) {
                state = state
                        .withVariable(PlannerNode.REPOSITORY_ID_KEY, "edd-repository")
                        .withVariable(PlannerNode.USER_ID_KEY, "edd-user")
                        .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString());
            }
            AgentState result = node.execute(state);
            String route = result.variables().get(PlannerNode.ROUTE_KEY);
            String response = scenario.expectedAgent()
                    ? result.variables().getOrDefault(PlannerNode.PLAN_KEY, "")
                    : result.variables().getOrDefault(PlannerNode.FINAL_RESPONSE_KEY, "");
            boolean passed = scenario.expectedAgent() ? PlannerNode.AGENT_ROUTE.equals(route)
                    : PlannerNode.CHAT_ROUTE.equals(route);
            passed = passed && scenario.responseGate().test(response)
                    && !result.variables().containsKey(PlannerNode.ERROR_KEY);
            String stateError = result.variables().getOrDefault(PlannerNode.ERROR_KEY, "");
            return new EddScenarioResult(
                    scenario.id(), passed, route, responsePreview(response),
                    Duration.between(started, Instant.now()).toMillis(), stateError);
        } catch (Throwable throwable) {
            return new EddScenarioResult(
                    scenario.id(), false, "", "",
                    Duration.between(started, Instant.now()).toMillis(), stackTrace(throwable));
        }
    }

    private void writeReport(
            EddConfiguration configuration,
            List<EddScenarioResult> results,
            int modelCallAttempts,
            ObjectMapper objectMapper) {
        try {
            Path directory = Path.of("target", "edd");
            Files.createDirectories(directory);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    directory.resolve("llm-edd-" + Instant.now().toEpochMilli() + ".json").toFile(),
                    new EddReport(
                            configuration.baseUrl(),
                            Instant.now(),
                            "live-openai-compatible",
                            modelCallAttempts,
                            results));
        } catch (Exception exception) {
            throw new IllegalStateException("写入 LLM EDD 报告失败", exception);
        }
    }

    private ModelRouter router(
            EddConfiguration configuration,
            LlmClient client,
            AtomicInteger modelCallAttempts) {
        EnumMap<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        for (TaskType taskType : TaskType.values()) {
            routes.put(taskType, List.of(
                    endpoint(taskType.name().toLowerCase() + "-primary",
                            modelFor(taskType, configuration), client),
                    endpoint(taskType.name().toLowerCase() + "-fallback",
                            configuration.fallbackModel(), client)));
        }
        return new ModelRouter(routes, start -> {
            modelCallAttempts.incrementAndGet();
            return com.agent.core.observability.ModelCallObserver.noop().start(start);
        });
    }

    private String modelFor(TaskType taskType, EddConfiguration configuration) {
        return switch (taskType) {
            case CODE -> configuration.codeModel();
            case VISION -> configuration.visionModel();
            case QUICK_CLASSIFICATION -> configuration.quickClassificationModel();
        };
    }

    private ModelEndpoint endpoint(String name, String model, LlmClient client) {
        return new ModelEndpoint(name, model, client, CircuitBreaker.ofDefaults(name));
    }

    private String responsePreview(String response) {
        if (response == null) {
            return "";
        }
        String compact = response.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "…";
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private record EddScenario(
            String id,
            String prompt,
            boolean expectedAgent,
            List<ChatMessage> history,
            MemoryContext memoryContext,
            java.util.function.Predicate<String> responseGate) {

        private EddScenario(
                String id,
                String prompt,
                boolean expectedAgent,
                java.util.function.Predicate<String> responseGate) {
            this(id, prompt, expectedAgent, List.of(), new MemoryContext("", 0), responseGate);
        }

        private EddScenario(
                String id,
                String prompt,
                boolean expectedAgent,
                List<ChatMessage> history,
                java.util.function.Predicate<String> responseGate) {
            this(id, prompt, expectedAgent, history, new MemoryContext("", 0), responseGate);
        }

        private EddScenario(
                String id,
                String prompt,
                boolean expectedAgent,
                MemoryContext memoryContext,
                java.util.function.Predicate<String> responseGate) {
            this(id, prompt, expectedAgent, List.of(), memoryContext, responseGate);
        }

        private EddScenario {
            Objects.requireNonNull(id, "id 不能为空");
            Objects.requireNonNull(prompt, "prompt 不能为空");
            history = List.copyOf(Objects.requireNonNull(history, "history 不能为空"));
            Objects.requireNonNull(memoryContext, "memoryContext 不能为空");
            Objects.requireNonNull(responseGate, "responseGate 不能为空");
        }
    }

    private record EddScenarioResult(
            String id,
            boolean passed,
            String route,
            String responsePreview,
            long durationMs,
            String error) {
    }

    private record EddReport(
            String endpoint,
            Instant generatedAt,
            String transport,
            int modelCallAttempts,
            List<EddScenarioResult> scenarios) {
    }

    private record RagEnhancerReport(
            String endpoint,
            Instant generatedAt,
            List<RagEnhancerResult> scenarios) {
    }

    private record RagEnhancerResult(
            String taskId,
            boolean passed,
            String responsePreview,
            String error) {
    }

    private record EddConfiguration(
            String baseUrl,
            String apiKey,
            String chatCompletionsPath,
            String codeModel,
            String visionModel,
            String quickClassificationModel,
            String fallbackModel) {

        private static EddConfiguration fromEnvironment() {
            return new EddConfiguration(
                    required("AGENT_LLM_BASE_URL"),
                    required("AGENT_LLM_API_KEY"),
                    valueOrDefault("AGENT_LLM_CHAT_COMPLETIONS_PATH", "/v1/chat/completions"),
                    required("AGENT_LLM_CODE_MODEL"),
                    required("AGENT_LLM_VISION_MODEL"),
                    required("AGENT_LLM_QUICK_CLASSIFICATION_MODEL"),
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
}
