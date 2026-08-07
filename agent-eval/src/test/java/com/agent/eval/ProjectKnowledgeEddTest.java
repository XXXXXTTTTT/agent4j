package com.agent.eval;

import com.agent.core.context.TokenEstimator;
import com.agent.core.context.Utf8TokenEstimator;
import com.agent.core.intent.TaskComplexity;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeContextRequest;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.knowledge.ProjectKnowledgeCompiler;
import com.agent.rag.knowledge.RagKnowledgeContextProvider;
import com.agent.rag.pipeline.HypotheticalDocumentGenerator;
import com.agent.rag.pipeline.QueryRewriter;
import com.agent.rag.pipeline.RagRetrievalPipeline;
import com.agent.rag.pipeline.RagRetrievalPolicy;
import com.agent.rag.pipeline.RagReranker;
import com.agent.rag.pipeline.RerankedHit;
import com.agent.rag.search.RagRetriever;
import com.agent.core.knowledge.KnowledgeEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 对项目知识编译与 RAG 组合执行确定性 EDD。 */
@Tag("edd")
class ProjectKnowledgeEddTest {

    private static final UUID PARENT_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_ID =
            UUID.fromString("51000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    @Test
    void evaluatesProjectKnowledgeScenariosAndWritesAuditReport() throws Exception {
        List<EddResult> results = List.of(
                orderedRules(),
                ignoresWrongCase(),
                detectsContentHashReload(),
                skipsCompleteFilesAtTokenBoundary(),
                recordsEnhancementDegradation(),
                fallsBackOnNonStrictBaseFailure());

        Path report = Path.of("target", "edd", "project-knowledge-edd.json");
        Files.createDirectories(report.getParent());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(report.toFile(), new EddReport(Instant.now(), results));

        var reportJson = mapper.readTree(report.toFile());
        assertThat(reportJson.path("scenarios")).hasSize(6);
        Set<String> requiredFields = Set.of(
                "taskId", "passed", "sourceCount", "fingerprint",
                "estimatedTokens", "degraded", "evidence");
        for (var scenario : reportJson.path("scenarios")) {
            List<String> fieldNames = new ArrayList<>();
            scenario.fieldNames().forEachRemaining(fieldNames::add);
            assertThat(fieldNames).containsExactlyInAnyOrderElementsOf(requiredFields);
        }
        assertThat(results).allSatisfy(result -> {
            assertThat(result.passed()).as(result.taskId() + " EDD 失败").isTrue();
            assertThat(result.fingerprint()).isNotBlank();
            assertThat(result.estimatedTokens()).isGreaterThanOrEqualTo(0);
            assertThat(result.evidence()).isNotNull();
        });
        assertThat(report).isRegularFile();
    }

    private EddResult orderedRules() throws Exception {
        Path root = workspace("root rules");
        Path nested = Files.createDirectories(root.resolve("src/app"));
        Files.writeString(root.resolve("SOUL.md"), "soul", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("CLAUDE.md"), "claude-root", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/AGENTS.md"), "agents-src", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/CLAUDE.md"), "claude-src", StandardCharsets.UTF_8);
        Files.writeString(nested.resolve("AGENTS.md"), "agents-app", StandardCharsets.UTF_8);
        KnowledgeContext context = provider(new Utf8TokenEstimator(),
                pipeline(query -> List.of(hit()), (query, limit) -> List.of()), true)
                .load(request(root, nested, "query", 1_000, TaskComplexity.SIMPLE));
        List<String> paths = context.evidence().stream()
                .filter(item -> item.source().endsWith(".md"))
                .map(KnowledgeEvidence::source)
                .toList();
        return result("knowledge.order", paths.equals(List.of(
                "SOUL.md", "AGENTS.md", "src/AGENTS.md", "src/app/AGENTS.md",
                "CLAUDE.md", "src/CLAUDE.md")), context);
    }

    private EddResult ignoresWrongCase() throws Exception {
        Path root = workspace("root rules");
        Path other = Files.createDirectories(root.resolve("other"));
        Files.writeString(other.resolve("agents.md"), "wrong case", StandardCharsets.UTF_8);
        KnowledgeContext context = provider(new Utf8TokenEstimator(),
                pipeline(query -> List.of(hit()), (query, limit) -> List.of()), true)
                .load(request(root, root, "query", 1_000, TaskComplexity.SIMPLE));
        boolean passed = context.evidence().stream()
                .noneMatch(item -> item.source().equals("other/agents.md"));
        return result("knowledge.case-sensitive", passed, context);
    }

    private EddResult detectsContentHashReload() throws Exception {
        Path root = workspace("version-one");
        Path agents = root.resolve("AGENTS.md");
        ProjectKnowledgeCompiler compiler = new ProjectKnowledgeCompiler(new Utf8TokenEstimator());
        var first = compiler.compile(root, root, 1_000);
        var same = compiler.compile(root, root, 1_000);
        FileTime originalTime = Files.getLastModifiedTime(agents);
        Files.writeString(agents, "version-two", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(agents, originalTime);
        var changed = compiler.compile(root, root, 1_000);
        boolean passed = first == same && !first.fingerprint().equals(changed.fingerprint());
        KnowledgeContext context = provider(new Utf8TokenEstimator(),
                pipeline(query -> List.of(hit()), (query, limit) -> List.of()), true)
                .load(request(root, root, "query", 1_000, TaskComplexity.SIMPLE));
        return result("knowledge.content-hash-reload", passed, context);
    }

    private EddResult skipsCompleteFilesAtTokenBoundary() throws Exception {
        Path root = workspace("rules");
        Files.writeString(root.resolve("SOUL.md"), "soul", StandardCharsets.UTF_8);
        TokenEstimator estimator = message -> {
            String text = ((com.agent.core.llm.ChatMessage.TextContent) message.content()).text();
            if (text.contains("项目规则")) {
                return text.contains("soul") ? 20 : 5;
            }
            return text.lines().filter(line -> line.startsWith("### [")).count() > 1 ? 20 : 5;
        };
        KnowledgeContext context = provider(estimator,
                pipeline(query -> List.of(hit()), (query, limit) -> List.of()), true)
                .load(request(root, root, "query", 9, TaskComplexity.SIMPLE));
        boolean passed = context.sourceCount() == 2 && !context.prompt().contains("soul");
        return result("knowledge.complete-file-budget", passed, context);
    }

    private EddResult recordsEnhancementDegradation() throws Exception {
        Path root = workspace("rules");
        KnowledgeContext context = provider(new Utf8TokenEstimator(),
                pipeline(query -> List.of(hit()), (query, limit) -> {
                    throw new IllegalStateException("rewrite unavailable");
                }), true)
                .load(request(root, root, "query", 1_000, TaskComplexity.STANDARD));
        boolean passed = context.degraded() && context.evidence().stream()
                .anyMatch(item -> item.status().name().equals("DEGRADED")
                        && item.errorStack().contains("rewrite unavailable"));
        return result("knowledge.rag-enhancement-degraded", passed, context);
    }

    private EddResult fallsBackOnNonStrictBaseFailure() throws Exception {
        Path root = workspace("rules");
        KnowledgeContext context = provider(new Utf8TokenEstimator(),
                pipeline(query -> {
                    throw new IllegalArgumentException("database unavailable");
                }, (query, limit) -> List.of()), false)
                .load(request(root, root, "query", 1_000, TaskComplexity.SIMPLE));
        boolean passed = context.degraded()
                && context.sourceCount() == 1
                && context.evidence().stream().anyMatch(item ->
                item.source().equals("RAG_PIPELINE")
                        && item.errorStack().contains("database unavailable"));
        return result("knowledge.rag-base-fallback", passed, context);
    }

    private Path workspace(String rules) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve(UUID.randomUUID().toString()));
        Files.writeString(root.resolve("AGENTS.md"), rules, StandardCharsets.UTF_8);
        return root;
    }

    private RagKnowledgeContextProvider provider(
            TokenEstimator estimator,
            RagRetrievalPipeline pipeline,
            boolean strict) {
        return new RagKnowledgeContextProvider(
                new ProjectKnowledgeCompiler(estimator),
                pipeline,
                new RagRetrievalPolicy(2, false, 10, 5, 100),
                estimator,
                strict);
    }

    private KnowledgeContextRequest request(
            Path root, Path active, String query, int maxTokens, TaskComplexity complexity) {
        return new KnowledgeContextRequest(
                "repository", "user", root, active, query, complexity, maxTokens);
    }

    private RagRetrievalPipeline pipeline(
            RagRetriever retriever,
            QueryRewriter rewriter) {
        EmbeddingModel embedding = new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return new float[8];
            }
        };
        HypotheticalDocumentGenerator hyde = query -> "unused";
        RagReranker reranker = (query, hits, limit) -> hits.stream()
                .limit(limit)
                .map(hit -> new RerankedHit(hit.hit().childChunk().childId(), hit.score()))
                .toList();
        return new RagRetrievalPipeline(
                retriever, embedding, rewriter, hyde, reranker, message -> 2);
    }

    private RagHit hit() {
        ParentChunk parent = new ParentChunk(
                PARENT_ID, "repository", "src/App.java", "demo.App",
                "class App { void run() {} }", 1, 5, "{}");
        ChildChunk child = new ChildChunk(
                CHILD_ID, PARENT_ID, "repository", "src/App.java", "demo.App#run()",
                0, "void run() {}", 2, 4, new float[8]);
        return new RagHit(child, parent, 0.4, 0.3, 0.2, 0.35);
    }

    private EddResult result(String taskId, boolean passed, KnowledgeContext context) {
        return new EddResult(taskId, passed, context.sourceCount(), context.fingerprint(),
                context.estimatedTokens(), context.degraded(), context.evidence());
    }

    private record EddReport(Instant generatedAt, List<EddResult> scenarios) {
    }

    private record EddResult(
            String taskId,
            boolean passed,
            int sourceCount,
            String fingerprint,
            int estimatedTokens,
            boolean degraded,
            List<KnowledgeEvidence> evidence) {
    }
}
