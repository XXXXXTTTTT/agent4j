package com.agent.eval;

import com.agent.core.context.TokenEstimator;
import com.agent.core.intent.TaskComplexity;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import com.agent.rag.domain.RagQuery;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.pipeline.FusedHit;
import com.agent.rag.pipeline.HypotheticalDocumentGenerator;
import com.agent.rag.pipeline.RagContentSource;
import com.agent.rag.pipeline.RagContextDocument;
import com.agent.rag.pipeline.RagRetrievalPipeline;
import com.agent.rag.pipeline.RagRetrievalPolicy;
import com.agent.rag.pipeline.RagRetrievalRequest;
import com.agent.rag.pipeline.RagRetrievalResult;
import com.agent.rag.pipeline.RagReranker;
import com.agent.rag.pipeline.RagStage;
import com.agent.rag.pipeline.RagStageEvidence;
import com.agent.rag.pipeline.RagStageStatus;
import com.agent.rag.pipeline.RerankedHit;
import com.agent.rag.pipeline.QueryRewriter;
import com.agent.rag.search.RagRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** 对 3A 自适应 RAG 流水线执行确定性 EDD，并保存可审计报告。 */
@Tag("edd")
class RagPipelineEddTest {

    private static final String REPOSITORY = "edd-rag-repository";
    private static final RagRetrievalPolicy DEFAULT_POLICY =
            new RagRetrievalPolicy(3, false, 10, 5, 100);

    @Test
    void evaluatesRagScenariosAndWritesSixStageAuditReport() throws Exception {
        List<EddResult> results = List.of(
                fuzzyQueryRewrite(),
                hydeReplacement(),
                duplicateQueryFusion(),
                rerankOrdering(),
                parentToChildBudgetFallback(),
                rewriteFailureDegradation(),
                hydeFailureDegradation(),
                rerankFailureDegradation());

        Path report = Path.of("target", "edd", "rag-pipeline-edd.json");
        Files.createDirectories(report.getParent());
        new ObjectMapper().findAndRegisterModules()
                .writerWithDefaultPrettyPrinter()
                .writeValue(report.toFile(), new EddReport(Instant.now(), results));

        assertThat(results).hasSize(8);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.passed()).as(result.taskId() + " EDD 失败").isTrue();
            assertThat(result.evidence()).extracting(RagStageEvidence::stage)
                    .containsExactly(RagStage.values());
            assertThat(result.estimatedTokens()).isEqualTo(result.documents().stream()
                    .mapToInt(RagContextDocument::estimatedTokens).sum());
        });
        assertThat(report).isRegularFile();
    }

    private EddResult fuzzyQueryRewrite() {
        List<String> queries = new ArrayList<>();
        RagRetrievalPipeline pipeline = pipeline(
                query -> {
                    queries.add(query.query());
                    return List.of(hit("src/Travel.java", "travel planning", 1));
                },
                (query, limit) -> List.of(" travel planning ", "travel planning"),
                query -> "unused",
                this::firstHit);
        RagRetrievalResult result = pipeline.retrieve(request(
                "模糊的出游问题", TaskComplexity.STANDARD, DEFAULT_POLICY));
        boolean passed = queries.equals(List.of("模糊的出游问题", "travel planning"))
                && status(result, RagStage.QUERY_REWRITE) == RagStageStatus.APPLIED;
        return result("rag.query-rewrite", passed, result);
    }

    private EddResult hydeReplacement() {
        AtomicReference<String> embedded = new AtomicReference<>();
        List<RagQuery> queries = new ArrayList<>();
        RagRetrievalPipeline pipeline = pipelineWithEmbedding(
                query -> {
                    queries.add(query);
                    return List.of(hit("src/Hyde.java", "hyde evidence", 2));
                },
                (query, limit) -> List.of("hyde alternate"),
                query -> {
                    embedded.set(query);
                    return "hypothetical implementation";
                },
                this::firstHit,
                text -> {
                    embedded.set(text);
                    return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
                });
        RagRetrievalResult result = pipeline.retrieve(request(
                "复杂代码问题", TaskComplexity.COMPLEX,
                new RagRetrievalPolicy(2, true, 10, 5, 100)));
        boolean passed = "hypothetical implementation".equals(embedded.get())
                && queries.size() == 2
                && queries.getFirst().queryEmbedding() != null
                && queries.get(1).queryEmbedding() == null
                && status(result, RagStage.HYDE) == RagStageStatus.APPLIED;
        return result("rag.hyde", passed, result);
    }

    private EddResult duplicateQueryFusion() {
        RagHit first = hit("src/A.java", "shared result", 3);
        RagHit second = hit("src/B.java", "single result", 4);
        RagRetrievalPipeline pipeline = pipeline(
                query -> query.query().equals("original")
                        ? List.of(first, second) : List.of(first),
                (query, limit) -> List.of("alternate"),
                query -> "unused",
                this::allHits);
        RagRetrievalResult result = pipeline.retrieve(request(
                "original", TaskComplexity.STANDARD,
                new RagRetrievalPolicy(2, false, 10, 5, 100)));
        boolean passed = result.documents().size() == 2
                && result.evidence().stream().filter(item -> item.stage() == RagStage.FUSION)
                .findFirst().orElseThrow().inputCount() == 3;
        return result("rag.rrf-duplicate", passed, result);
    }

    private EddResult rerankOrdering() {
        RagHit first = hit("src/A.java", "first", 5);
        RagHit second = hit("src/B.java", "second", 6);
        RagRetrievalPipeline pipeline = pipeline(
                query -> List.of(first, second),
                (query, limit) -> List.of(),
                query -> "unused",
                (query, hits, limit) -> List.of(
                        new RerankedHit(second.childChunk().childId(), 1.0),
                        new RerankedHit(first.childChunk().childId(), 0.5)));
        RagRetrievalResult result = pipeline.retrieve(request(
                "rerank", TaskComplexity.SIMPLE, DEFAULT_POLICY));
        boolean passed = result.documents().getFirst().path().equals("src/B.java")
                && status(result, RagStage.RERANK) == RagStageStatus.APPLIED;
        return result("rag.rerank-order", passed, result);
    }

    private EddResult parentToChildBudgetFallback() {
        RagHit first = hit("src/Large.java", "large parent", 7);
        RagHit second = hit("src/Small.java", "small child", 8);
        RagRetrievalPipeline pipeline = pipelineWithEstimator(
                query -> List.of(first, second),
                (query, limit) -> List.of(),
                query -> "unused",
                this::allHits,
                textEstimator(text -> switch (text) {
                    case "large parent parent" -> 8;
                    case "small child parent" -> 8;
                    case "small child" -> 2;
                    default -> 1;
                }));
        RagRetrievalResult result = pipeline.retrieve(request(
                "budget", TaskComplexity.SIMPLE,
                new RagRetrievalPolicy(1, false, 10, 5, 10)));
        boolean passed = result.documents().stream()
                .map(RagContextDocument::contentSource)
                .toList().equals(List.of(RagContentSource.PARENT, RagContentSource.CHILD));
        return result("rag.parent-child-budget", passed, result);
    }

    private EddResult rewriteFailureDegradation() {
        RagRetrievalPipeline pipeline = pipeline(
                query -> List.of(hit("src/Rewrite.java", "rewrite", 9)),
                (query, limit) -> { throw new IllegalStateException("rewrite failure"); },
                query -> "unused", this::firstHit);
        RagRetrievalResult result = pipeline.retrieve(request(
                "rewrite failure", TaskComplexity.STANDARD, DEFAULT_POLICY));
        return result("rag.failure-rewrite",
                status(result, RagStage.QUERY_REWRITE) == RagStageStatus.DEGRADED, result);
    }

    private EddResult hydeFailureDegradation() {
        RagRetrievalPipeline pipeline = pipeline(
                query -> List.of(hit("src/HydeFailure.java", "hyde failure", 10)),
                (query, limit) -> List.of(),
                query -> { throw new IllegalArgumentException("hyde failure"); },
                this::firstHit);
        RagRetrievalResult result = pipeline.retrieve(request(
                "hyde failure", TaskComplexity.COMPLEX,
                new RagRetrievalPolicy(1, true, 10, 5, 100)));
        return result("rag.failure-hyde",
                status(result, RagStage.HYDE) == RagStageStatus.DEGRADED, result);
    }

    private EddResult rerankFailureDegradation() {
        RagRetrievalPipeline pipeline = pipeline(
                query -> List.of(hit("src/RerankFailure.java", "rerank failure", 11)),
                (query, limit) -> List.of(), query -> "unused",
                (query, hits, limit) -> { throw new UnsupportedOperationException("rerank failure"); });
        RagRetrievalResult result = pipeline.retrieve(request(
                "rerank failure", TaskComplexity.SIMPLE, DEFAULT_POLICY));
        return result("rag.failure-rerank",
                status(result, RagStage.RERANK) == RagStageStatus.DEGRADED, result);
    }

    private RagRetrievalPipeline pipeline(
            RagRetriever retriever,
            QueryRewriter rewriter,
            HypotheticalDocumentGenerator hyde,
            RagReranker reranker) {
        return pipelineWithEstimator(
                retriever, rewriter, hyde, reranker, textEstimator(text -> 1));
    }

    private RagRetrievalPipeline pipelineWithEmbedding(
            RagRetriever retriever,
            QueryRewriter rewriter,
            HypotheticalDocumentGenerator hyde,
            RagReranker reranker,
            Function<String, float[]> embeddingFunction) {
        return new RagRetrievalPipeline(
                retriever, embeddingModel(embeddingFunction), rewriter, hyde,
                reranker, textEstimator(text -> 1));
    }

    private RagRetrievalPipeline pipelineWithEstimator(
            RagRetriever retriever,
            QueryRewriter rewriter,
            HypotheticalDocumentGenerator hyde,
            RagReranker reranker,
            TokenEstimator estimator) {
        return new RagRetrievalPipeline(
                retriever, embeddingModel(), rewriter, hyde, reranker, estimator);
    }

    private EmbeddingModel embeddingModel() {
        return embeddingModel(text -> new float[]{1, 0, 0, 0, 0, 0, 0, 0});
    }

    private EmbeddingModel embeddingModel(Function<String, float[]> embeddingFunction) {
        return new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return embeddingFunction.apply(text);
            }
        };
    }

    private TokenEstimator textEstimator(Function<String, Integer> estimator) {
        return message -> estimator.apply(
                ((com.agent.core.llm.ChatMessage.TextContent) message.content()).text());
    }

    private List<RerankedHit> firstHit(String query, List<FusedHit> hits, int limit) {
        return List.of(new RerankedHit(
                hits.getFirst().hit().childChunk().childId(), 1.0));
    }

    private List<RerankedHit> allHits(String query, List<FusedHit> hits, int limit) {
        return hits.stream().limit(limit)
                .map(hit -> new RerankedHit(
                        hit.hit().childChunk().childId(), hit.score()))
                .toList();
    }

    private RagRetrievalRequest request(
            String query, TaskComplexity complexity, RagRetrievalPolicy policy) {
        return new RagRetrievalRequest(REPOSITORY, query, complexity, policy);
    }

    private RagStageStatus status(RagRetrievalResult result, RagStage stage) {
        return result.evidence().stream()
                .filter(item -> item.stage() == stage)
                .findFirst().orElseThrow().status();
    }

    private EddResult result(String taskId, boolean passed, RagRetrievalResult result) {
        return new EddResult(taskId, passed, result.documents(), result.estimatedTokens(),
                result.degraded(), result.evidence());
    }

    private RagHit hit(String path, String content, int ordinal) {
        UUID parentId = UUID.nameUUIDFromBytes(
                (REPOSITORY + path).getBytes(StandardCharsets.UTF_8));
        UUID childId = UUID.nameUUIDFromBytes(
                (path + ordinal).getBytes(StandardCharsets.UTF_8));
        ParentChunk parent = new ParentChunk(parentId, REPOSITORY, path,
                "demo." + path.substring(path.lastIndexOf('/') + 1, path.lastIndexOf('.')),
                content + " parent", 1, 10, "{}");
        ChildChunk child = new ChildChunk(childId, parentId, REPOSITORY, path,
                parent.symbol() + "#run()", ordinal, content, 2, 4,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        return new RagHit(child, parent, 0.4, 0.3, 0.2, 0.35);
    }

    private record EddReport(Instant generatedAt, List<EddResult> scenarios) {
    }

    private record EddResult(
            String taskId,
            boolean passed,
            List<RagContextDocument> documents,
            int estimatedTokens,
            boolean degraded,
            List<RagStageEvidence> evidence) {
    }
}
