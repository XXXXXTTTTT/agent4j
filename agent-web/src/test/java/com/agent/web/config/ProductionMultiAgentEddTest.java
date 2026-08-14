package com.agent.web.config;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.llm.InferenceCapability;
import com.agent.core.llm.InferenceServiceContract;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.observability.ModelCallObserver;
import com.agent.core.observability.ModelCallSpan;
import com.agent.core.orchestration.AgentRole;
import com.agent.core.orchestration.OrchestrationMode;
import com.agent.core.multiagent.AgentHandoffEvent;
import com.agent.core.multiagent.AgentHandoffExecutor;
import com.agent.web.orchestration.ProductionMultiAgentOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用配置的真实 OpenAI 兼容网关验证并行研究子 Agent。 */
@Tag("edd")
class ProductionMultiAgentEddTest {

    private static final String ENABLED_ENV = "AGENT_LLM_ENABLED";
    private static final String BASE_URL_ENV = "AGENT_LLM_BASE_URL";
    private static final String API_KEY_ENV = "AGENT_LLM_API_KEY";
    private static final String PATH_ENV = "AGENT_LLM_CHAT_COMPLETIONS_PATH";
    private static final String CODE_MODEL_ENV = "AGENT_LLM_CODE_MODEL";
    private static final String FALLBACK_MODEL_ENV = "AGENT_LLM_FALLBACK_MODEL";
    private static final String RESEARCH_GROUP = "edd-research";

    @Test
    void runsParallelResearchAgainstConfiguredRealModel() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv(ENABLED_ENV)),
                ENABLED_ENV + " 未开启，跳过真实模型 EDD");
        String baseUrl = required(BASE_URL_ENV);
        String apiKey = required(API_KEY_ENV);
        String completionPath = required(PATH_ENV);
        String codeModel = required(CODE_MODEL_ENV);
        String fallbackModel = required(FALLBACK_MODEL_ENV);

        Path workspace = Files.createTempDirectory("agent4j-multiagent-edd-");
        Files.writeString(workspace.resolve("Calculator.java"),
                "class Calculator { int add(int a, int b) { return a + b; } }\n");
        Files.writeString(workspace.resolve("CalculatorTest.java"),
                "class CalculatorTest { void addWorks() { new Calculator().add(1, 2); } }\n");

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient clientTransport = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        LlmClient client = new LlmClient(clientTransport, objectMapper, completionPath);
        AtomicInteger modelCallCount = new AtomicInteger();
        ModelCallObserver observer = start -> {
            modelCallCount.incrementAndGet();
            return ModelCallObserver.noop().start(start);
        };
        ModelEndpoint codeEndpoint = endpoint("edd-code", codeModel, client);
        ModelEndpoint fallbackEndpoint = endpoint("edd-fallback", fallbackModel, client);
        Map<TaskType, List<ModelEndpoint>> routes = routes(codeEndpoint, fallbackEndpoint);
        ModelRouter router = new ModelRouter(
                routes,
                Map.of(RESEARCH_GROUP, routes),
                null,
                observer);

        UUID parentRunId = UUID.randomUUID();
        List<AgentHandoffEvent> handoffEvents = new CopyOnWriteArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("endpoint", baseUrl + completionPath);
        report.put("models", List.of(codeModel, fallbackModel));
        report.put("parentRunId", parentRunId.toString());
        report.put("childRunIds", List.of());
        report.put("handoffLifecycle", List.of());
        report.put("workspaceFiles", List.of("Calculator.java", "CalculatorTest.java"));
        report.put("modelCallCount", 0);
        report.put("researchResultSummaries", List.of());
        report.put("passed", false);
        try (client;
             AgentHandoffExecutor executor = new AgentHandoffExecutor(
                     ProductionMultiAgentOrchestrator.catalog(),
                     graphRegistry(new ProductionGraphConfiguration(), router),
                     handoffEvents::add);
             ProductionMultiAgentOrchestrator orchestrator =
                     new ProductionMultiAgentOrchestrator(executor)) {
            AgentState initial = AgentState.empty()
                    .withVariable(PlannerNode.PLAN_KEY, "检查 Calculator.java 的实现与测试覆盖")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString())
                    .withVariable(ProductionMultiAgentOrchestrator.MODE_KEY,
                            OrchestrationMode.PARALLEL_RESEARCH.name())
                    .withVariable(ProductionMultiAgentOrchestrator.MODEL_GROUP_KEY_PREFIX
                            + AgentRole.RESEARCHER.name(), RESEARCH_GROUP);
            AgentState result = orchestrator.researchNode().execute(
                    new NodeExecutionContext(parentRunId, "research"), initial);

            List<UUID> childRunIds = handoffEvents.stream()
                    .map(AgentHandoffEvent::childRunId)
                    .distinct()
                    .toList();
            List<String> lifecycle = handoffEvents.stream()
                    .map(this::lifecycle)
                    .toList();
            List<String> summaries = List.of(
                    result.variables().get("research.codeEvidence"),
                    result.variables().get("research.testEvidence"));
            report.put("childRunIds", childRunIds.stream().map(UUID::toString).toList());
            report.put("handoffLifecycle", lifecycle);
            report.put("modelCallCount", modelCallCount.get());
            report.put("researchResultSummaries", summaries.stream()
                    .map(this::summary)
                    .toList());
            boolean passed = childRunIds.size() == 2
                    && lifecycle.contains("STARTED")
                    && lifecycle.contains("COMPLETED")
                    && modelCallCount.get() >= 2
                    && summaries.stream().allMatch(value -> value != null && !value.isBlank());
            report.put("passed", passed);
            assertThat(passed).isTrue();
        } finally {
            Path reportPath = Path.of("target", "edd",
                    "production-multi-agent-" + Instant.now().toEpochMilli() + ".json");
            Files.createDirectories(reportPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        }
    }

    private GraphRegistry graphRegistry(ProductionGraphConfiguration configuration, ModelRouter router) {
        GraphFactory code = configuration.multiAgentResearchCodeGraph(router);
        GraphFactory tests = configuration.multiAgentResearchTestsGraph(router);
        return new GraphRegistry(Map.of(
                "multiagent-research-code", code,
                "multiagent-research-tests", tests));
    }

    private static Map<TaskType, List<ModelEndpoint>> routes(
            ModelEndpoint codeEndpoint, ModelEndpoint fallbackEndpoint) {
        Map<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        routes.put(TaskType.CODE, List.of(codeEndpoint, fallbackEndpoint));
        routes.put(TaskType.VISION, List.of(codeEndpoint, fallbackEndpoint));
        routes.put(TaskType.QUICK_CLASSIFICATION, List.of(codeEndpoint, fallbackEndpoint));
        return Map.copyOf(routes);
    }

    private static ModelEndpoint endpoint(String name, String model, LlmClient client) {
        return new ModelEndpoint(name, model, client,
                CircuitBreaker.ofDefaults(name + "-breaker"),
                new InferenceServiceContract(name, model,
                        com.agent.core.llm.InferenceProtocol.OPENAI_CHAT_COMPLETIONS,
                        Set.of(InferenceCapability.CHAT_COMPLETIONS)));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 未配置");
        }
        return value.trim();
    }

    private String lifecycle(AgentHandoffEvent event) {
        return switch (event) {
            case AgentHandoffEvent.Started ignored -> "STARTED";
            case AgentHandoffEvent.NodeStarted ignored -> "NODE_STARTED";
            case AgentHandoffEvent.NodeProgress ignored -> "NODE_PROGRESS";
            case AgentHandoffEvent.NodeCompleted ignored -> "NODE_COMPLETED";
            case AgentHandoffEvent.Completed ignored -> "COMPLETED";
            case AgentHandoffEvent.Failed ignored -> "FAILED";
        };
    }

    private String summary(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240);
    }
}
