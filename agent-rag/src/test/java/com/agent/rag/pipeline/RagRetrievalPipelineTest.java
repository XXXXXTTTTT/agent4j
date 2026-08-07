package com.agent.rag.pipeline;

import com.agent.core.context.TokenEstimator;
import com.agent.core.intent.TaskComplexity;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import com.agent.rag.domain.RagQuery;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.search.RagRetriever;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagRetrievalPipelineTest {

    private static final UUID PARENT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_ID =
            UUID.fromString("31000000-0000-0000-0000-000000000001");

    @Test
    void rewritesComplexQueriesAndUsesHydeOnlyForOriginalVectorRetrieval() {
        List<RagQuery> queries = new ArrayList<>();
        AtomicInteger rewriteLimit = new AtomicInteger(-1);
        AtomicReference<String> embeddedText = new AtomicReference<>();
        float[] hydeEmbedding = new float[]{1, 0, 0, 0, 0, 0, 0, 0};
        RagRetriever retriever = query -> {
            queries.add(query);
            return List.of(hit());
        };
        QueryRewriter rewriter = (query, limit) -> {
            rewriteLimit.set(limit);
            return List.of(" rewrite-a ", "", "original", "rewrite-a", "rewrite-b");
        };
        EmbeddingModel embeddingModel = embeddingModel(text -> {
            embeddedText.set(text);
            return hydeEmbedding;
        });
        RagRetrievalPipeline pipeline = pipeline(
                retriever,
                embeddingModel,
                rewriter,
                query -> "hypothetical document",
                (query, hits, limit) -> List.of(
                        new RerankedHit(CHILD_ID, 0.9)));

        RagRetrievalResult result = pipeline.retrieve(request(
                TaskComplexity.COMPLEX,
                new RagRetrievalPolicy(3, true, 10, 5, 100)));

        assertThat(rewriteLimit).hasValue(2);
        assertThat(embeddedText).hasValue("hypothetical document");
        assertThat(queries).extracting(RagQuery::query)
                .containsExactly("original", "rewrite-a", "rewrite-b");
        assertThat(queries).extracting(RagQuery::repositoryId)
                .containsOnly("repository");
        assertThat(queries).extracting(RagQuery::limit).containsOnly(10);
        assertThat(queries.getFirst().queryEmbedding()).containsExactly(hydeEmbedding);
        assertThat(queries.get(1).queryEmbedding()).isNull();
        assertThat(queries.get(2).queryEmbedding()).isNull();
        assertThat(result.evidence()).extracting(RagStageEvidence::stage)
                .containsExactly(RagStage.values());
        assertThat(result.evidence()).extracting(RagStageEvidence::status)
                .containsExactly(
                        RagStageStatus.APPLIED,
                        RagStageStatus.APPLIED,
                        RagStageStatus.APPLIED,
                        RagStageStatus.APPLIED,
                        RagStageStatus.APPLIED,
                        RagStageStatus.APPLIED);
        assertThat(result.documents()).hasSize(1);
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void appliesComplexitySpecificQueryLimitsAndSkipsHydeBelowComplex() {
        AtomicInteger simpleRewriteCalls = new AtomicInteger();
        AtomicInteger simpleHydeCalls = new AtomicInteger();
        List<RagQuery> simpleQueries = new ArrayList<>();
        RagRetrievalPipeline simple = pipeline(
                query -> {
                    simpleQueries.add(query);
                    return List.of(hit());
                },
                embeddingModel(text -> new float[8]),
                (query, limit) -> {
                    simpleRewriteCalls.incrementAndGet();
                    return List.of("unused");
                },
                query -> {
                    simpleHydeCalls.incrementAndGet();
                    return "unused";
                },
                firstHitReranker());

        RagRetrievalResult simpleResult = simple.retrieve(request(
                TaskComplexity.SIMPLE,
                new RagRetrievalPolicy(3, true, 10, 5, 100)));

        assertThat(simpleQueries).hasSize(1);
        assertThat(simpleRewriteCalls).hasValue(0);
        assertThat(simpleHydeCalls).hasValue(0);
        assertThat(simpleResult.evidence().get(0).status())
                .isEqualTo(RagStageStatus.SKIPPED);
        assertThat(simpleResult.evidence().get(1).status())
                .isEqualTo(RagStageStatus.SKIPPED);

        AtomicInteger standardLimit = new AtomicInteger();
        List<RagQuery> standardQueries = new ArrayList<>();
        RagRetrievalPipeline standard = pipeline(
                query -> {
                    standardQueries.add(query);
                    return List.of(hit());
                },
                embeddingModel(text -> new float[8]),
                (query, limit) -> {
                    standardLimit.set(limit);
                    return List.of("standard-a", "standard-b");
                },
                query -> {
                    throw new AssertionError("STANDARD 不应执行 HyDE");
                },
                firstHitReranker());

        standard.retrieve(request(
                TaskComplexity.STANDARD,
                new RagRetrievalPolicy(3, true, 10, 5, 100)));

        assertThat(standardLimit).hasValue(1);
        assertThat(standardQueries).extracting(RagQuery::query)
                .containsExactly("original", "standard-a");
    }

    @Test
    void degradesEnhancementFailuresWithCompleteStackTraces() {
        IllegalStateException rewriteFailure = new IllegalStateException("rewrite failed");
        RagRetrievalResult rewriteResult = pipeline(
                query -> List.of(hit()),
                embeddingModel(text -> new float[8]),
                (query, limit) -> {
                    throw rewriteFailure;
                },
                query -> "hyde",
                firstHitReranker()).retrieve(request(
                        TaskComplexity.STANDARD,
                        new RagRetrievalPolicy(2, false, 10, 5, 100)));
        assertDegraded(rewriteResult, RagStage.QUERY_REWRITE, "rewrite failed");

        IllegalArgumentException hydeFailure = new IllegalArgumentException("hyde failed");
        RagRetrievalResult hydeResult = pipeline(
                query -> List.of(hit()),
                embeddingModel(text -> new float[8]),
                (query, limit) -> List.of(),
                query -> {
                    throw hydeFailure;
                },
                firstHitReranker()).retrieve(request(
                        TaskComplexity.COMPLEX,
                        new RagRetrievalPolicy(1, true, 10, 5, 100)));
        assertDegraded(hydeResult, RagStage.HYDE, "hyde failed");

        UnsupportedOperationException rerankFailure =
                new UnsupportedOperationException("rerank failed");
        RagRetrievalResult rerankResult = pipeline(
                query -> List.of(hit()),
                embeddingModel(text -> new float[8]),
                (query, limit) -> List.of(),
                query -> "unused",
                (query, hits, limit) -> {
                    throw rerankFailure;
                }).retrieve(request(
                        TaskComplexity.SIMPLE,
                        new RagRetrievalPolicy(1, false, 10, 5, 100)));
        assertDegraded(rerankResult, RagStage.RERANK, "rerank failed");
        assertThat(rerankResult.documents()).hasSize(1);
    }

    @Test
    void terminatesOnEmbeddingOrBaseRetrievalFailuresWithoutLosingCause() {
        IllegalStateException embeddingFailure = new IllegalStateException("embedding failed");
        RagRetrievalPipeline embeddingPipeline = pipeline(
                query -> List.of(hit()),
                embeddingModel(text -> {
                    throw embeddingFailure;
                }),
                (query, limit) -> List.of(),
                query -> "hyde",
                firstHitReranker());

        assertThatThrownBy(() -> embeddingPipeline.retrieve(request(
                TaskComplexity.COMPLEX,
                new RagRetrievalPolicy(1, true, 10, 5, 100))))
                .isInstanceOfSatisfying(
                        RagPipelineException.class,
                        exception -> assertThat(exception.getCause())
                                .isSameAs(embeddingFailure));

        IllegalArgumentException retrievalFailure =
                new IllegalArgumentException("retrieval failed");
        RagRetrievalPipeline retrievalPipeline = pipeline(
                query -> {
                    throw retrievalFailure;
                },
                embeddingModel(text -> new float[8]),
                (query, limit) -> List.of(),
                query -> "unused",
                firstHitReranker());

        assertThatThrownBy(() -> retrievalPipeline.retrieve(request(
                TaskComplexity.SIMPLE,
                new RagRetrievalPolicy(1, false, 10, 5, 100))))
                .isInstanceOfSatisfying(
                        RagPipelineException.class,
                        exception -> assertThat(exception.getCause())
                                .isSameAs(retrievalFailure));
    }

    private RagRetrievalPipeline pipeline(
            RagRetriever retriever,
            EmbeddingModel embeddingModel,
            QueryRewriter rewriter,
            HypotheticalDocumentGenerator hyde,
            RagReranker reranker) {
        TokenEstimator estimator = message -> 5;
        return new RagRetrievalPipeline(
                retriever, embeddingModel, rewriter, hyde, reranker, estimator);
    }

    private RagReranker firstHitReranker() {
        return (query, hits, limit) -> List.of(new RerankedHit(
                hits.getFirst().hit().childChunk().childId(), 0.9));
    }

    private EmbeddingModel embeddingModel(
            java.util.function.Function<String, float[]> embeddingFunction) {
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

    private RagRetrievalRequest request(
            TaskComplexity complexity, RagRetrievalPolicy policy) {
        return new RagRetrievalRequest(
                "repository", "original", complexity, policy);
    }

    private void assertDegraded(
            RagRetrievalResult result, RagStage stage, String failureMessage) {
        assertThat(result.degraded()).isTrue();
        RagStageEvidence evidence = result.evidence().stream()
                .filter(item -> item.stage() == stage)
                .findFirst()
                .orElseThrow();
        assertThat(evidence.status()).isEqualTo(RagStageStatus.DEGRADED);
        assertThat(evidence.errorStack())
                .contains("java.lang")
                .contains(failureMessage)
                .contains("RagRetrievalPipelineTest");
    }

    private RagHit hit() {
        ParentChunk parent = new ParentChunk(
                PARENT_ID,
                "repository",
                "src/App.java",
                "demo.App",
                "class App { void original() {} }",
                1,
                5,
                "{}");
        ChildChunk child = new ChildChunk(
                CHILD_ID,
                PARENT_ID,
                "repository",
                "src/App.java",
                "demo.App#original()",
                0,
                "void original() {}",
                2,
                4,
                new float[8]);
        return new RagHit(child, parent, 0.4, 0.3, 0.2, 0.35);
    }
}
