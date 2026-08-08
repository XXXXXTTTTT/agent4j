package com.agent.web.config;

import com.agent.core.context.Utf8TokenEstimator;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.LlmClient;
import com.agent.core.llm.ModelEndpoint;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.TaskType;
import com.agent.core.memory.MemoryContext;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.ReviewerNode;
import com.agent.core.trace.RunLogPublisher;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.index.CodebaseIndexCoordinator;
import com.agent.rag.ingest.CodebaseIngestionService;
import com.agent.rag.ingest.RepositorySourceScanner;
import com.agent.rag.knowledge.IndexingKnowledgeContextProvider;
import com.agent.rag.knowledge.ProjectKnowledgeCompiler;
import com.agent.rag.knowledge.RagKnowledgeContextProvider;
import com.agent.rag.pipeline.LexicalCoverageReranker;
import com.agent.rag.pipeline.RagRetrievalPipeline;
import com.agent.rag.pipeline.RagRetrievalPolicy;
import com.agent.rag.pipeline.RagPipelineException;
import com.agent.rag.search.HybridRagRetriever;
import com.agent.rag.store.JdbcRagStore;
import com.agent.rag.store.RagRepositoryIndex;
import com.agent.sandbox.ast.AstService;
import com.agent.sandbox.ast.WorkspaceSnapshotService;
import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.SandboxTerminalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProductionKnowledgeIntegrationTest {

    private static final String BASE_URL = "https://knowledge-agent.test";
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static DataSource dataSource;

    @TempDir
    Path workspace;

    @BeforeAll
    static void startPostgres() {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "Docker Engine 不可用: " + exception.getMessage());
            return;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker Engine 不可用");
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new KnowledgeRagConfiguration().ragFlyway(dataSource).migrate();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    void answersProjectQuestionFromRealIndexAndStopsAfterPlanner() throws Exception {
        writeWorkspace("architectureRule", "currentArchitecture");
        JdbcRagStore store = new JdbcRagStore(dataSource, Clock.systemUTC());
        EmbeddingModel embedding = constantEmbedding();
        CodebaseIndexCoordinator indexingCoordinator = coordinator(store, embedding);
        IndexingKnowledgeContextProvider knowledgeProvider = provider(
                indexingCoordinator, store, embedding, true);
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(builder.build(), objectMapper, COMPLETIONS_PATH);
        ModelEndpoint endpoint = new ModelEndpoint(
                "knowledge", "code-model", client,
                CircuitBreaker.ofDefaults("knowledge-breaker"));
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(endpoint),
                TaskType.VISION, List.of(endpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(endpoint)));
        server.expect(requestTo(BASE_URL + COMPLETIONS_PATH))
                .andRespond(withSuccess(response(objectMapper), MediaType.APPLICATION_JSON));
        Path bash = Files.createFile(workspace.resolve("bash.exe"));
        GraphFactory factory = new ProductionGraphConfiguration().codeAgentGraph(
                properties(bash, "knowledge-repository"),
                router,
                request -> {
                    throw new AssertionError("项目问答不应读取长期记忆");
                },
                knowledgeProvider,
                mock(SandboxTerminalService.class),
                mock(BrowserAutomation.class),
                new AstService(),
                new WorkspaceSnapshotService(50, 64_000),
                RunLogPublisher.noop(),
                objectMapper);

        try (indexingCoordinator; client; StateGraph graph = factory.create()) {
            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "请解释当前仓库架构")
                    .withVariable(PlannerNode.REPOSITORY_ID_KEY, "knowledge-repository")
                    .withVariable(PlannerNode.USER_ID_KEY, "user-a")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

            assertThat(result.trace()).containsExactly("planner");
            assertThat(result.variables())
                    .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.KNOWLEDGE_ROUTE)
                    .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "基于项目证据的架构回答")
                    .containsEntry(PlannerNode.KNOWLEDGE_DEGRADED_KEY, "false")
                    .doesNotContainKeys(
                            CoderNode.UNIFIED_DIFF_KEY,
                            OpsNode.COMMAND_KEY,
                            ReviewerNode.APPROVED_KEY);
            assertThat(result.variables().get(PlannerNode.KNOWLEDGE_CONTEXT_KEY))
                    .contains("architectureRule", "currentArchitecture");
            assertThat(store.findRepositoryIndex("knowledge-repository")).isPresent();
        }
        server.verify();
    }

    @Test
    void sharesRealFirstIndexAndPreservesOldIndexAfterFailedRefresh() throws Exception {
        writeWorkspace("ruleBefore", "methodBefore");
        JdbcRagStore store = new JdbcRagStore(dataSource, Clock.systemUTC());
        BlockingEmbeddingModel embedding = new BlockingEmbeddingModel();
        CodebaseIndexCoordinator coordinator = coordinator(store, embedding);
        try {
            CompletableFuture<RagRepositoryIndex> first =
                    coordinator.ensureIndexed(workspace, "single-flight-repository");
            assertThat(embedding.started.await(15, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<RagRepositoryIndex> second =
                    coordinator.ensureIndexed(workspace, "single-flight-repository");
            assertThat(second).isSameAs(first);
            embedding.release.countDown();
            RagRepositoryIndex oldIndex = first.get(20, TimeUnit.SECONDS);
            assertThat(embedding.calls.get()).isEqualTo(oldIndex.childCount());

            Files.writeString(workspace.resolve("src/App.java"), """
                    package sample;
                    final class App { void methodAfter() {} }
                    """, StandardCharsets.UTF_8);
            CodebaseIndexCoordinator failing = coordinator(store, new FailingEmbeddingModel());
            try {
                assertThatThrownBy(() -> failing.ensureIndexed(
                        workspace, "single-flight-repository").join())
                        .hasCauseInstanceOf(IllegalStateException.class);
                assertThat(store.findRepositoryIndex("single-flight-repository"))
                        .contains(oldIndex);
                assertThat(store.findByLexical(
                        "single-flight-repository", "methodBefore", 10)).isNotEmpty();
                assertThat(store.findByLexical(
                        "single-flight-repository", "methodAfter", 10)).isEmpty();
            } finally {
                failing.close();
            }
        } finally {
            embedding.release.countDown();
            coordinator.close();
        }
    }

    @Test
    void injectsMemoryAndRealProjectKnowledgeBeforeExecutingCodeTask() throws Exception {
        writeWorkspace("必须先运行测试", "methodBefore");
        Files.writeString(workspace.resolve("value.txt"), "before\n", StandardCharsets.UTF_8);
        Path bash = Files.createFile(workspace.resolve("bash.exe"));
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 代码任务通过真实 Git 工作区应用 Unified Diff。
        }

        JdbcRagStore store = new JdbcRagStore(dataSource, Clock.systemUTC());
        EmbeddingModel embedding = constantEmbedding();
        CodebaseIndexCoordinator coordinator = coordinator(store, embedding);
        IndexingKnowledgeContextProvider knowledgeProvider = provider(
                coordinator, store, embedding, true);
        AtomicInteger memoryCalls = new AtomicInteger();
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(builder.build(), objectMapper, COMPLETIONS_PATH);
        ModelEndpoint endpoint = new ModelEndpoint(
                "code", "code-model", client,
                CircuitBreaker.ofDefaults("code-knowledge-breaker"));
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(endpoint),
                TaskType.VISION, List.of(endpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(endpoint)));
        server.expect(times(3), requestTo(BASE_URL + COMPLETIONS_PATH))
                .andRespond(request -> withSuccess(
                        responseForCodeRequest(
                                objectMapper,
                                ((MockClientHttpRequest) request).getBodyAsString()),
                        MediaType.APPLICATION_JSON).createResponse(request));

        SandboxTerminalService terminalService = mock(SandboxTerminalService.class);
        when(terminalService.execute(any(), any())).thenReturn(
                CompletableFuture.completedFuture(
                        new CommandResult(0, "after\n", "", false)));
        GraphFactory factory = new ProductionGraphConfiguration().codeAgentGraph(
                properties(bash, "code-knowledge-repository"),
                router,
                request -> {
                    memoryCalls.incrementAndGet();
                    return new MemoryContext("保持最小变更", 1);
                },
                knowledgeProvider,
                terminalService,
                mock(BrowserAutomation.class),
                new AstService(),
                new WorkspaceSnapshotService(50, 64_000),
                RunLogPublisher.noop(),
                objectMapper);

        try (coordinator; client; StateGraph graph = factory.create()) {
            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "把 value.txt 改成 after 并运行测试")
                    .withVariable(PlannerNode.REPOSITORY_ID_KEY, "code-knowledge-repository")
                    .withVariable(PlannerNode.USER_ID_KEY, "user-a")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

            assertThat(memoryCalls).hasValue(1);
            assertThat(result.trace())
                    .containsExactly("planner", "coder", "ops", "reviewer");
            assertThat(result.variables())
                    .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.AGENT_ROUTE)
                    .containsEntry(PlannerNode.MEMORY_CONTEXT_KEY, "保持最小变更")
                    .containsEntry(PlannerNode.KNOWLEDGE_DEGRADED_KEY, "false")
                    .containsEntry(CoderNode.UPDATED_FILES_KEY, "value.txt")
                    .containsEntry(OpsNode.EXIT_CODE_KEY, "0")
                    .containsEntry(ReviewerNode.APPROVED_KEY, "true")
                    .doesNotContainKeys(
                            PlannerNode.ERROR_KEY,
                            CoderNode.ERROR_KEY,
                            OpsNode.ERROR_KEY,
                            ReviewerNode.ERROR_KEY);
            assertThat(result.variables().get(PlannerNode.REQUEST_KEY))
                    .contains("保持最小变更", "必须先运行测试", "src/App.java");
            assertThat(result.variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY))
                    .contains("PROJECT_FILE", "RAG_STAGE");
            assertThat(Files.readString(workspace.resolve("value.txt")))
                    .isEqualTo("after\n");
            assertThat(store.findRepositoryIndex("code-knowledge-repository"))
                    .isPresent();
        }
        server.verify();
    }

    @Test
    void answersWithProjectFilesWhenBaseRagFailsInNonStrictMode() throws Exception {
        writeWorkspace("故障时保留项目规则", "methodBefore");
        Path bash = Files.createFile(workspace.resolve("bash.exe"));
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient client = new LlmClient(builder.build(), objectMapper, COMPLETIONS_PATH);
        ModelEndpoint endpoint = new ModelEndpoint(
                "fallback", "code-model", client,
                CircuitBreaker.ofDefaults("fallback-breaker"));
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(endpoint),
                TaskType.VISION, List.of(endpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(endpoint)));
        server.expect(requestTo(BASE_URL + COMPLETIONS_PATH))
                .andRespond(withSuccess(response(objectMapper), MediaType.APPLICATION_JSON));
        GraphFactory factory = new ProductionGraphConfiguration().codeAgentGraph(
                properties(bash, "fallback-repository"),
                router,
                request -> {
                    throw new AssertionError("项目问答不应读取长期记忆");
                },
                failingKnowledgeProvider(false),
                mock(SandboxTerminalService.class),
                mock(BrowserAutomation.class),
                new AstService(),
                new WorkspaceSnapshotService(50, 64_000),
                RunLogPublisher.noop(),
                objectMapper);

        try (client; StateGraph graph = factory.create()) {
            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "请解释当前仓库架构")
                    .withVariable(PlannerNode.REPOSITORY_ID_KEY, "fallback-repository")
                    .withVariable(PlannerNode.USER_ID_KEY, "user-a")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

            assertThat(result.trace()).containsExactly("planner");
            assertThat(result.variables())
                    .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.KNOWLEDGE_ROUTE)
                    .containsEntry(PlannerNode.KNOWLEDGE_DEGRADED_KEY, "true")
                    .containsEntry(PlannerNode.FINAL_RESPONSE_KEY, "基于项目证据的架构回答")
                    .doesNotContainKeys(
                            PlannerNode.ERROR_KEY,
                            CoderNode.UNIFIED_DIFF_KEY,
                            OpsNode.COMMAND_KEY,
                            ReviewerNode.APPROVED_KEY);
            assertThat(result.variables().get(PlannerNode.KNOWLEDGE_CONTEXT_KEY))
                    .contains("故障时保留项目规则")
                    .doesNotContain("src/App.java");
            assertThat(result.variables().get(PlannerNode.KNOWLEDGE_EVIDENCE_KEY))
                    .contains("RAG_PIPELINE", "DEGRADED", "database unavailable");
        }
        server.verify();
    }

    @Test
    void stopsWithCompleteCauseWhenBaseRagFailsInStrictMode() throws Exception {
        writeWorkspace("严格模式规则", "methodBefore");
        Path bash = Files.createFile(workspace.resolve("bash.exe"));
        ObjectMapper objectMapper = new ObjectMapper();
        LlmClient client = new LlmClient(
                RestClient.builder().baseUrl(BASE_URL).build(),
                objectMapper,
                COMPLETIONS_PATH);
        ModelEndpoint endpoint = new ModelEndpoint(
                "strict", "code-model", client,
                CircuitBreaker.ofDefaults("strict-breaker"));
        ModelRouter router = new ModelRouter(Map.of(
                TaskType.CODE, List.of(endpoint),
                TaskType.VISION, List.of(endpoint),
                TaskType.QUICK_CLASSIFICATION, List.of(endpoint)));
        GraphFactory factory = new ProductionGraphConfiguration().codeAgentGraph(
                properties(bash, "strict-repository"),
                router,
                request -> {
                    throw new AssertionError("项目问答不应读取长期记忆");
                },
                failingKnowledgeProvider(true),
                mock(SandboxTerminalService.class),
                mock(BrowserAutomation.class),
                new AstService(),
                new WorkspaceSnapshotService(50, 64_000),
                RunLogPublisher.noop(),
                objectMapper);

        try (client; StateGraph graph = factory.create()) {
            AgentState result = graph.execute(AgentState.empty()
                    .withVariable(PlannerNode.TASK_KEY, "请解释当前仓库架构")
                    .withVariable(PlannerNode.REPOSITORY_ID_KEY, "strict-repository")
                    .withVariable(PlannerNode.USER_ID_KEY, "user-a")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, workspace.toString()));

            assertThat(result.trace()).containsExactly("planner");
            assertThat(result.variables())
                    .containsEntry(PlannerNode.ROUTE_KEY, PlannerNode.FAILED_ROUTE)
                    .doesNotContainKeys(
                            PlannerNode.FINAL_RESPONSE_KEY,
                            CoderNode.UNIFIED_DIFF_KEY,
                            OpsNode.COMMAND_KEY,
                            ReviewerNode.APPROVED_KEY);
            assertThat(result.variables().get(PlannerNode.ERROR_KEY))
                    .contains(RagPipelineException.class.getName())
                    .contains("database unavailable")
                    .contains("IllegalArgumentException");
        }
    }

    private void writeWorkspace(String rule, String method) throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), rule, StandardCharsets.UTF_8);
        Path source = workspace.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package sample;
                final class App { void %s() {} }
                """.formatted(method), StandardCharsets.UTF_8);
    }

    private IndexingKnowledgeContextProvider provider(
            CodebaseIndexCoordinator coordinator,
            JdbcRagStore store,
            EmbeddingModel embedding,
            boolean strict) {
        Utf8TokenEstimator estimator = new Utf8TokenEstimator();
        HybridRagRetriever retriever = new HybridRagRetriever(store, embedding);
        RagRetrievalPipeline pipeline = new RagRetrievalPipeline(
                retriever,
                embedding,
                (query, limit) -> List.of(),
                query -> "unused",
                new LexicalCoverageReranker(),
                estimator);
        RagKnowledgeContextProvider delegate = new RagKnowledgeContextProvider(
                new ProjectKnowledgeCompiler(estimator),
                pipeline,
                new RagRetrievalPolicy(1, false, 20, 8, 2_000),
                estimator,
                strict);
        return new IndexingKnowledgeContextProvider(
                coordinator, delegate, Duration.ofSeconds(10));
    }

    private RagKnowledgeContextProvider failingKnowledgeProvider(boolean strict) {
        Utf8TokenEstimator estimator = new Utf8TokenEstimator();
        RagRetrievalPipeline pipeline = new RagRetrievalPipeline(
                query -> {
                    throw new IllegalArgumentException("database unavailable");
                },
                constantEmbedding(),
                (query, limit) -> List.of(),
                query -> "unused",
                new LexicalCoverageReranker(),
                estimator);
        return new RagKnowledgeContextProvider(
                new ProjectKnowledgeCompiler(estimator),
                pipeline,
                new RagRetrievalPolicy(1, false, 20, 8, 2_000),
                estimator,
                strict);
    }

    private CodebaseIndexCoordinator coordinator(
            JdbcRagStore store,
            EmbeddingModel embedding) {
        return new CodebaseIndexCoordinator(
                new RepositorySourceScanner(),
                new CodebaseIngestionService(new AstService(), embedding, store),
                store);
    }

    private EmbeddingModel constantEmbedding() {
        return new EmbeddingModel() {
            @Override
            public int dimensions() {
                return ChildChunk.EMBEDDING_DIMENSIONS;
            }

            @Override
            public float[] embed(String text) {
                return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
            }
        };
    }

    private ProductionAgentProperties properties(Path bash, String repositoryId) {
        return new ProductionAgentProperties(
                true, workspace, repositoryId, "user-a", "", "PTY", bash.toString(),
                "python:3.12-slim", "/workspace", "", "",
                Duration.ofSeconds(30), Duration.ofSeconds(15),
                50, 64_000, 2, 12, 1_800_000, 120_000, 200_000, 3, 12_000);
    }

    private String response(ObjectMapper objectMapper) throws Exception {
        var response = objectMapper.createObjectNode();
        response.put("id", "knowledge-response");
        response.put("object", "chat.completion");
        response.put("created", 1L);
        response.put("model", "code-model");
        var choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", "基于项目证据的架构回答");
        choice.put("finish_reason", "stop");
        return objectMapper.writeValueAsString(response);
    }

    private String responseForCodeRequest(ObjectMapper objectMapper, String body)
            throws IOException {
        if (body.contains("最终质量审查节点")) {
            return completionResponse(
                    objectMapper,
                    "{\"approved\":true,\"summary\":\"验证通过\",\"feedback\":\"无需修改\"}");
        }
        if (body.contains("代码修改节点")) {
            return completionResponse(objectMapper, objectMapper.writeValueAsString(Map.of(
                    "summary", "更新 value.txt",
                    "unifiedDiff", validDiff(),
                    "command", "cat value.txt")));
        }
        return completionResponse(objectMapper, "修改 value.txt 并运行 cat value.txt");
    }

    private String completionResponse(ObjectMapper objectMapper, String content)
            throws IOException {
        var response = objectMapper.createObjectNode();
        response.put("id", "code-response");
        response.put("object", "chat.completion");
        response.put("created", 1L);
        response.put("model", "code-model");
        var choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        choice.put("finish_reason", "stop");
        return objectMapper.writeValueAsString(response);
    }

    private String validDiff() {
        return """
                diff --git a/value.txt b/value.txt
                --- a/value.txt
                +++ b/value.txt
                @@ -1 +1 @@
                -before
                +after
                """;
    }

    private static class CountingEmbeddingModel implements EmbeddingModel {
        protected final AtomicInteger calls = new AtomicInteger();

        @Override
        public int dimensions() {
            return ChildChunk.EMBEDDING_DIMENSIONS;
        }

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
        }
    }

    private static final class BlockingEmbeddingModel extends CountingEmbeddingModel {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public float[] embed(String text) {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("embedding release timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("embedding interrupted", exception);
            }
            return super.embed(text);
        }
    }

    private static final class FailingEmbeddingModel extends CountingEmbeddingModel {
        @Override
        public float[] embed(String text) {
            throw new IllegalStateException("embedding refresh failed");
        }
    }
}
