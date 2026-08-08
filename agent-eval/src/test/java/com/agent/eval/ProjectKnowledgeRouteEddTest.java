package com.agent.eval;

import com.agent.core.context.Utf8TokenEstimator;
import com.agent.core.engine.AgentState;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.memory.MemoryContext;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.index.CodebaseIndexCoordinator;
import com.agent.rag.ingest.CodebaseIngestionService;
import com.agent.rag.ingest.RepositorySourceScanner;
import com.agent.rag.knowledge.IndexingKnowledgeContextProvider;
import com.agent.rag.knowledge.ProjectKnowledgeCompiler;
import com.agent.rag.knowledge.RagKnowledgeContextProvider;
import com.agent.rag.pipeline.HypotheticalDocumentGenerator;
import com.agent.rag.pipeline.LexicalCoverageReranker;
import com.agent.rag.pipeline.QueryRewriter;
import com.agent.rag.pipeline.RagRetrievalPipeline;
import com.agent.rag.pipeline.RagRetrievalPolicy;
import com.agent.rag.pipeline.RerankedHit;
import com.agent.rag.search.RagRetriever;
import com.agent.rag.store.RagRepositoryIndex;
import com.agent.rag.store.RagStore;
import com.agent.rag.store.RetrievalRow;
import com.agent.sandbox.ast.AstService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;

/** 对项目知识生产路线执行确定性端到端评测，并写入固定审计报告。 */
@Tag("edd")
class ProjectKnowledgeRouteEddTest {

    private static final String BASE_URL = "https://project-knowledge-edd.test";
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String REPOSITORY = "edd-project-repository";
    private static final UUID PARENT_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_ID =
            UUID.fromString("61000000-0000-0000-0000-000000000001");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path temporaryDirectory;

    @Test
    void evaluatesSixProjectKnowledgeRoutesAndWritesExactAuditFields() throws Exception {
        List<EddResult> results = List.of(
                normalChat(),
                projectQuestion(),
                codeTask(),
                persistedIndexSkip(),
                enhancementDegradation(),
                baseFailureFallback());

        Path report = Path.of("target", "edd", "project-knowledge-route-edd.json");
        Files.createDirectories(report.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(report.toFile(), new EddReport(results));

        JsonNode reportJson = objectMapper.readTree(report.toFile());
        assertThat(reportJson.path("scenarios")).hasSize(6);
        for (JsonNode scenario : reportJson.path("scenarios")) {
            List<String> fieldNames = new ArrayList<>();
            scenario.fieldNames().forEachRemaining(fieldNames::add);
            assertThat(fieldNames).containsExactlyInAnyOrderElementsOf(SetFields.EXACT);
        }
        assertThat(results).allSatisfy(result -> {
            assertThat(result.passed()).as(result.taskId() + " EDD 失败").isTrue();
            assertThat(result.route()).isNotBlank();
            assertThat(result.sourceCount()).isGreaterThanOrEqualTo(0);
            assertThat(result.fingerprint()).isNotNull();
            assertThat(result.ragStages()).isNotNull();
            assertThat(result.ttftMs()).isGreaterThanOrEqualTo(0);
        });
        assertThat(report).isRegularFile();
    }

    private EddResult normalChat() throws Exception {
        PlannerRun run = runPlanner(
                "你是什么模型", null, KnowledgeContextProvider.empty(),
                new MemoryContext("", 0), true);
        return result("route.chat", run, PlannerNode.CHAT_ROUTE,
                run.state().variables().containsKey(PlannerNode.FINAL_RESPONSE_KEY),
                PlannerNode.FINAL_RESPONSE_KEY);
    }

    private EddResult projectQuestion() throws Exception {
        Path root = workspace("项目规则：所有架构回答必须引用当前代码证据");
        KnowledgeContextProvider provider = ragProvider(
                pipeline(query -> List.of(hit()), (query, limit) -> List.of()), true);
        PlannerRun run = runPlanner(
                "请解释当前项目架构", root, provider,
                new MemoryContext("", 0), true);
        return result("route.project-question", run, PlannerNode.KNOWLEDGE_ROUTE,
                run.state().variables().get(PlannerNode.KNOWLEDGE_CONTEXT_KEY)
                        .contains("项目规则")
                        && run.state().variables().get(PlannerNode.REQUEST_KEY)
                        .contains("src/App.java")
                        && run.state().variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY)
                        .contains("RAG_STAGE"),
                PlannerNode.FINAL_RESPONSE_KEY);
    }

    private EddResult codeTask() throws Exception {
        Path root = workspace("代码任务必须保持最小变更并运行测试");
        PlannerRun run = runPlanner(
                "修改 src/App.java 并运行测试", root,
                ragProvider(pipeline(query -> List.of(hit()), (query, limit) -> List.of()), true),
                new MemoryContext("用户偏好：使用 Maven 验证", 1), true);
        return result("route.code-task", run, PlannerNode.AGENT_ROUTE,
                run.state().variables().get(PlannerNode.REQUEST_KEY)
                        .contains("用户偏好：使用 Maven 验证")
                        && run.state().variables().get(PlannerNode.REQUEST_KEY)
                        .contains("代码任务必须保持最小变更")
                        && run.state().variables().get(PlannerNode.REQUEST_KEY)
                        .contains("src/App.java")
                        && run.state().variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY)
                        .contains("RAG_STAGE"),
                PlannerNode.PLAN_KEY);
    }

    private EddResult persistedIndexSkip() throws Exception {
        Path root = workspace("持久化索引必须按内容指纹复用");
        CountingRagStore store = new CountingRagStore();
        EmbeddingModel embedding = embedding();
        CodebaseIndexCoordinator coordinator = new CodebaseIndexCoordinator(
                new RepositorySourceScanner(),
                new CodebaseIngestionService(new AstService(), embedding, store),
                store);
        try {
            store.replaceCalls.set(0);
            coordinator.ensureIndexed(root, REPOSITORY).join();
            PlannerRun run = runPlanner(
                    "请解释当前项目架构", root,
                    new IndexingKnowledgeContextProvider(
                            coordinator,
                            ragProvider(new RagRetrievalPipeline(
                                    new com.agent.rag.search.HybridRagRetriever(store, embedding),
                                    embedding,
                                    (query, limit) -> List.of(),
                                    query -> "unused",
                                    new LexicalCoverageReranker(),
                                    new Utf8TokenEstimator()), true),
                            java.time.Duration.ofSeconds(5)),
                    new MemoryContext("", 0), true);
            boolean passed = store.replaceCalls.get() == 1
                    && store.findRepositoryIndex(REPOSITORY).isPresent();
            return result("rag.persisted-index-skip", run, PlannerNode.KNOWLEDGE_ROUTE,
                    passed, PlannerNode.FINAL_RESPONSE_KEY);
        } finally {
            coordinator.close();
        }
    }

    private EddResult enhancementDegradation() throws Exception {
        Path root = workspace("增强检索失败时保留项目规则");
        PlannerRun run = runPlanner(
                "请解释当前项目架构", root,
                ragProvider(pipeline(
                        query -> List.of(hit()),
                        (query, limit) -> { throw new IllegalStateException("rewrite unavailable"); }),
                        true),
                new MemoryContext("", 0), true);
        return result("rag.enhancement-degradation", run, PlannerNode.KNOWLEDGE_ROUTE,
                "true".equals(run.state().variables().get(PlannerNode.KNOWLEDGE_DEGRADED_KEY))
                        && run.state().variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY)
                        .contains("QUERY_REWRITE")
                        && run.state().variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY)
                        .contains("DEGRADED")
                        && run.state().variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY)
                        .contains("rewrite unavailable"),
                PlannerNode.FINAL_RESPONSE_KEY);
    }

    private EddResult baseFailureFallback() throws Exception {
        Path root = workspace("基础召回失败时仅保留项目规则");
        PlannerRun run = runPlanner(
                "请解释当前项目架构", root,
                ragProvider(pipeline(
                        query -> { throw new IllegalArgumentException("database unavailable"); },
                        (query, limit) -> List.of()),
                        false),
                new MemoryContext("", 0), true);
        return result("rag.base-failure-fallback", run, PlannerNode.KNOWLEDGE_ROUTE,
                "true".equals(run.state().variables().get(PlannerNode.KNOWLEDGE_DEGRADED_KEY))
                        && run.state().variables().get(PlannerNode.KNOWLEDGE_CONTEXT_KEY)
                        .contains("基础召回失败时仅保留项目规则")
                        && run.state().variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY)
                        .contains("RAG_PIPELINE")
                        && run.state().variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY)
                        .contains("DEGRADED")
                        && run.state().variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY)
                        .contains("database unavailable"),
                PlannerNode.FINAL_RESPONSE_KEY);
    }

    private PlannerRun runPlanner(
            String task,
            Path root,
            KnowledgeContextProvider knowledgeProvider,
            MemoryContext memory,
            boolean expectModelCall) throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicLong firstResponseNanos = new AtomicLong();
        if (expectModelCall) {
            server.expect(anything()).andRespond(request -> {
                byte[] body = response().getBytes(StandardCharsets.UTF_8);
                InputStream responseBody = new FilterInputStream(
                        new ByteArrayInputStream(body)) {
                    @Override
                    public int read() throws IOException {
                        firstResponseNanos.compareAndSet(0, System.nanoTime());
                        return super.read();
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        firstResponseNanos.compareAndSet(0, System.nanoTime());
                        return super.read(bytes, offset, length);
                    }
                };
                return new MockClientHttpResponse(responseBody, HttpStatus.OK);
            });
        }
        LlmClient client = new LlmClient(builder.build(), objectMapper, COMPLETIONS_PATH);
        ModelEndpoint endpoint = new ModelEndpoint(
                "edd", "edd-model", client, CircuitBreaker.ofDefaults("edd-breaker"));
        EnumMap<TaskType, List<ModelEndpoint>> routes = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) {
            routes.put(type, List.of(endpoint));
        }
        AgentState state = AgentState.empty().withVariable(PlannerNode.TASK_KEY, task);
        if (root != null) {
            state = state
                    .withVariable(PlannerNode.REPOSITORY_ID_KEY, REPOSITORY)
                    .withVariable(PlannerNode.USER_ID_KEY, "edd-user")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, root.toString());
        }
        long started = System.nanoTime();
        try (client) {
            PlannerNode planner = new PlannerNode(
                    new ModelRouter(routes),
                    request -> memory,
                    5,
                    knowledgeProvider,
                    2_000,
                    objectMapper,
                    com.agent.core.nodes.PlannerPromptTemplates.catalog(),
                    PlannerNode.defaultContextWindowManager(),
                    null,
                    12_000);
            AgentState result = planner.execute(state);
            long elapsed = firstResponseNanos.get() == 0
                    ? java.time.Duration.ofNanos(System.nanoTime() - started).toMillis()
                    : java.time.Duration.ofNanos(firstResponseNanos.get() - started).toMillis();
            if (expectModelCall) {
                server.verify();
            }
            return new PlannerRun(result, elapsed);
        }
    }

    private EddResult result(
            String taskId,
            PlannerRun run,
            String expectedRoute,
            boolean scenarioPassed,
            String responseKey) throws IOException {
        Map<String, String> variables = run.state().variables();
        String evidenceJson = variables.getOrDefault(PlannerNode.KNOWLEDGE_EVIDENCE_KEY, "[]");
        JsonNode evidence = objectMapper.readTree(evidenceJson);
        List<String> stages = new ArrayList<>();
        evidence.forEach(item -> {
            if ("RAG_STAGE".equals(item.path("kind").asText())) {
                stages.add(item.path("source").asText());
            }
        });
        String response = variables.getOrDefault(responseKey, "");
        boolean passed = scenarioPassed
                && expectedRoute.equals(variables.get(PlannerNode.ROUTE_KEY))
                && !response.isBlank()
                && !variables.containsKey(PlannerNode.ERROR_KEY);
        return new EddResult(
                taskId,
                variables.getOrDefault(PlannerNode.ROUTE_KEY, "failed"),
                parseInt(variables.get(PlannerNode.KNOWLEDGE_SOURCES_KEY)),
                variables.getOrDefault(PlannerNode.KNOWLEDGE_FINGERPRINT_KEY, ""),
                List.copyOf(stages),
                "true".equals(variables.get(PlannerNode.KNOWLEDGE_DEGRADED_KEY)),
                run.ttftMillis(),
                response,
                passed);
    }

    private int parseInt(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private KnowledgeContextProvider ragProvider(
            RagRetrievalPipeline pipeline,
            boolean strict) {
        Utf8TokenEstimator estimator = new Utf8TokenEstimator();
        return new RagKnowledgeContextProvider(
                new ProjectKnowledgeCompiler(estimator),
                pipeline,
                new RagRetrievalPolicy(2, false, 20, 8, 2_000),
                estimator,
                strict);
    }

    private RagRetrievalPipeline pipeline(
            RagRetriever retriever,
            QueryRewriter rewriter) {
        EmbeddingModel embedding = embedding();
        HypotheticalDocumentGenerator hyde = query -> "unused";
        return new RagRetrievalPipeline(
                retriever,
                embedding,
                rewriter,
                hyde,
                (query, hits, limit) -> hits.stream()
                        .limit(limit)
                        .map(hit -> new RerankedHit(
                                hit.hit().childChunk().childId(), hit.score()))
                        .toList(),
                new Utf8TokenEstimator());
    }

    private EmbeddingModel embedding() {
        return new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
            }
        };
    }

    private Path workspace(String rules) throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve(UUID.randomUUID().toString()));
        Files.writeString(root.resolve("AGENTS.md"), rules, StandardCharsets.UTF_8);
        Path source = root.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; final class App { void run() {} }\n",
                StandardCharsets.UTF_8);
        return root;
    }

    private RagHit hit() {
        ParentChunk parent = new ParentChunk(
                PARENT_ID, REPOSITORY, "src/App.java", "demo.App",
                "class App { void run() {} }", 1, 5, "{}");
        ChildChunk child = new ChildChunk(
                CHILD_ID, PARENT_ID, REPOSITORY, "src/App.java", "demo.App#run()",
                0, "void run() {}", 2, 4, new float[8]);
        return new RagHit(child, parent, 0.4, 0.3, 0.2, 0.35);
    }

    private String response() throws IOException {
        var response = objectMapper.createObjectNode();
        response.put("id", "project-knowledge-edd");
        response.put("object", "chat.completion");
        response.put("created", 1L);
        response.put("model", "edd-model");
        var choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", "EDD final response");
        choice.put("finish_reason", "stop");
        return objectMapper.writeValueAsString(response);
    }

    private record PlannerRun(AgentState state, long ttftMillis) {
    }

    private record EddReport(List<EddResult> scenarios) {
    }

    private record EddResult(
            String taskId,
            String route,
            int sourceCount,
            String fingerprint,
            List<String> ragStages,
            boolean degraded,
            long ttftMs,
            String finalResponse,
            boolean passed) {
    }

    private static final class SetFields {
        private static final java.util.Set<String> EXACT = java.util.Set.of(
                "taskId", "route", "sourceCount", "fingerprint", "ragStages",
                "degraded", "ttftMs", "finalResponse", "passed");

        private SetFields() {
        }
    }

    private static final class CountingRagStore implements RagStore {
        private final AtomicInteger replaceCalls = new AtomicInteger();
        private final Map<String, List<ParentChunk>> parents = new HashMap<>();
        private final Map<String, List<ChildChunk>> children = new HashMap<>();
        private final Map<String, RagRepositoryIndex> indexes = new HashMap<>();

        @Override
        public synchronized void replaceRepository(
                String repositoryId,
                List<ParentChunk> parents,
                List<ChildChunk> children) {
            this.parents.put(repositoryId, List.copyOf(parents));
            this.children.put(repositoryId, List.copyOf(children));
            replaceCalls.incrementAndGet();
        }

        @Override
        public synchronized void replaceRepository(
                String repositoryId,
                List<ParentChunk> parents,
                List<ChildChunk> children,
                RagRepositoryIndex index) {
            replaceRepository(repositoryId, parents, children);
            indexes.put(repositoryId, index);
        }

        @Override
        public synchronized Optional<RagRepositoryIndex> findRepositoryIndex(String repositoryId) {
            return Optional.ofNullable(indexes.get(repositoryId));
        }

        @Override
        public synchronized List<RetrievalRow> findByVector(
                String repositoryId, float[] queryEmbedding, int limit) {
            return rows(repositoryId).stream().limit(limit).toList();
        }

        @Override
        public synchronized List<RetrievalRow> findByLexical(
                String repositoryId, String query, int limit) {
            return rows(repositoryId).stream().limit(limit).toList();
        }

        @Override
        public synchronized long countChildren(String repositoryId) {
            return children.getOrDefault(repositoryId, List.of()).size();
        }

        @Override
        public synchronized double averageDocumentLength(String repositoryId) {
            List<ChildChunk> values = children.getOrDefault(repositoryId, List.of());
            return values.isEmpty()
                    ? 1
                    : values.stream().mapToInt(item -> item.content().length()).average().orElse(1);
        }

        @Override
        public synchronized Map<String, Long> documentFrequencies(
                String repositoryId, List<String> terms) {
            List<ChildChunk> values = children.getOrDefault(repositoryId, List.of());
            Map<String, Long> frequencies = new HashMap<>();
            for (String term : terms) {
                frequencies.put(term, values.stream()
                        .filter(item -> item.content().contains(term))
                        .count());
            }
            return frequencies;
        }

        private List<RetrievalRow> rows(String repositoryId) {
            Map<UUID, ParentChunk> parentById = parents.getOrDefault(repositoryId, List.of())
                    .stream().collect(java.util.stream.Collectors.toMap(
                            ParentChunk::parentId, item -> item));
            return children.getOrDefault(repositoryId, List.of()).stream()
                    .map(child -> new RetrievalRow(child, parentById.get(child.parentId()), 1.0))
                    .toList();
        }
    }
}
