package com.agent.rag.knowledge;

import com.agent.core.context.TokenEstimator;
import com.agent.core.context.Utf8TokenEstimator;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.intent.TaskComplexity;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeContextRequest;
import com.agent.core.knowledge.KnowledgeEvidenceKind;
import com.agent.core.knowledge.KnowledgeEvidenceStatus;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.pipeline.HypotheticalDocumentGenerator;
import com.agent.rag.pipeline.QueryRewriter;
import com.agent.rag.pipeline.RagRetrievalPipeline;
import com.agent.rag.pipeline.RagRetrievalPolicy;
import com.agent.rag.pipeline.RagRetrievalRequest;
import com.agent.rag.pipeline.RagReranker;
import com.agent.rag.pipeline.RagStage;
import com.agent.rag.pipeline.RagStageStatus;
import com.agent.rag.pipeline.RerankedHit;
import com.agent.rag.pipeline.RagPipelineException;
import com.agent.rag.search.RagRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagKnowledgeContextProviderTest {

    private static final UUID PARENT_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_ID =
            UUID.fromString("41000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    @Test
    void combinesProjectRulesBeforeRagEvidenceWithImmutableAuditOrder() throws Exception {
        Path root = workspace("always use tests");
        RagKnowledgeContextProvider provider = provider(
                new Utf8TokenEstimator(),
                pipeline(query -> List.of(hit()), (query, limit) -> List.of()),
                true);

        KnowledgeContext context = provider.load(request(root, "find entry point", 1_000));

        assertThat(context.prompt())
                .contains("项目规则（受当前指令和安全策略约束）")
                .contains("按需检索的代码证据")
                .contains("[1] src/App.java:1-5 demo.App")
                .contains("class App");
        assertThat(context.sourceCount()).isEqualTo(2);
        assertThat(context.evidence()).extracting(item -> item.kind())
                .startsWith(KnowledgeEvidenceKind.PROJECT_FILE)
                .containsSubsequence(
                        KnowledgeEvidenceKind.RAG_STAGE,
                        KnowledgeEvidenceKind.RAG_STAGE,
                        KnowledgeEvidenceKind.RAG_STAGE);
        assertThat(context.evidence()).extracting(item -> item.status())
                .containsOnly(KnowledgeEvidenceStatus.APPLIED, KnowledgeEvidenceStatus.SKIPPED);
        assertThatThrownBy(() -> context.evidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(NodeExecutionContext.current()).isEmpty();
    }

    @Test
    void preservesEnhancementDegradationEvidenceAndCompleteStack() throws Exception {
        Path root = workspace("rules");
        IllegalStateException failure = new IllegalStateException("rewrite unavailable");
        RagKnowledgeContextProvider provider = provider(
                new Utf8TokenEstimator(),
                pipeline(query -> List.of(hit()), (query, limit) -> {
                    throw failure;
                }),
                true);

        KnowledgeContext context = provider.load(request(root, "query", 1_000, TaskComplexity.STANDARD));

        assertThat(context.degraded()).isTrue();
        assertThat(context.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.kind()).isEqualTo(KnowledgeEvidenceKind.RAG_STAGE);
            assertThat(evidence.status()).isEqualTo(KnowledgeEvidenceStatus.DEGRADED);
            assertThat(evidence.errorStack()).contains("rewrite unavailable", "RagKnowledgeContextProviderTest");
        });
    }

    @Test
    void dropsWholeCodeDocumentsWhenFinalPromptBudgetIsExceeded() throws Exception {
        Path root = workspace("rules");
        TokenEstimator estimator = message -> {
            String text = ((com.agent.core.llm.ChatMessage.TextContent) message.content()).text();
            if (text.contains("项目规则") && text.contains("src/App.java")) {
                return 20;
            }
            if (text.contains("项目规则")) {
                return 5;
            }
            return 5;
        };
        RagKnowledgeContextProvider provider = provider(
                estimator,
                pipeline(query -> List.of(hit()), (query, limit) -> List.of()),
                true);

        KnowledgeContext context = provider.load(request(root, "query", 9));

        assertThat(context.prompt()).contains("项目规则").doesNotContain("src/App.java");
        assertThat(context.sourceCount()).isEqualTo(1);
        assertThat(context.estimatedTokens()).isEqualTo(5);
    }

    @Test
    void fallsBackToProjectRulesOnBaseRagFailureOnlyWhenNonStrict() throws Exception {
        Path root = workspace("rules");
        IllegalArgumentException failure = new IllegalArgumentException("database unavailable");
        RagKnowledgeContextProvider provider = provider(
                new Utf8TokenEstimator(),
                pipeline(query -> {
                    throw failure;
                }, (query, limit) -> List.of()),
                false);

        KnowledgeContext context = provider.load(request(root, "query", 1_000));

        assertThat(context.prompt()).contains("项目规则").doesNotContain("[1]");
        assertThat(context.degraded()).isTrue();
        assertThat(context.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.source()).isEqualTo("RAG_PIPELINE");
            assertThat(evidence.status()).isEqualTo(KnowledgeEvidenceStatus.DEGRADED);
            assertThat(evidence.errorStack()).contains("database unavailable");
        });
    }

    @Test
    void preservesBaseRagCauseInStrictMode() throws Exception {
        Path root = workspace("rules");
        IllegalArgumentException failure = new IllegalArgumentException("database unavailable");
        RagKnowledgeContextProvider provider = provider(
                new Utf8TokenEstimator(),
                pipeline(query -> {
                    throw failure;
                }, (query, limit) -> List.of()),
                true);

        assertThatThrownBy(() -> provider.load(request(root, "query", 1_000)))
                .isInstanceOfSatisfying(RagPipelineException.class, exception ->
                        assertThat(exception.getCause()).isSameAs(failure));
    }

    private Path workspace(String rules) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve(UUID.randomUUID().toString()));
        Files.writeString(root.resolve("AGENTS.md"), rules, StandardCharsets.UTF_8);
        return root;
    }

    private KnowledgeContextRequest request(Path root, String query, int maxTokens) {
        return request(root, query, maxTokens, TaskComplexity.SIMPLE);
    }

    private KnowledgeContextRequest request(
            Path root, String query, int maxTokens, TaskComplexity complexity) {
        return new KnowledgeContextRequest(
                "repository", "user", root, root, query, complexity, maxTokens);
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
                PARENT_ID,
                "repository",
                "src/App.java",
                "demo.App",
                "class App { void run() {} }",
                1,
                5,
                "{}");
        ChildChunk child = new ChildChunk(
                CHILD_ID,
                PARENT_ID,
                "repository",
                "src/App.java",
                "demo.App#run()",
                0,
                "void run() {}",
                2,
                4,
                new float[8]);
        return new RagHit(child, parent, 0.4, 0.3, 0.2, 0.35);
    }
}
