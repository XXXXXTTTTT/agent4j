package com.agent.rag.pipeline;

import com.agent.core.intent.TaskComplexity;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagPipelineDomainTest {

    private static final UUID PARENT_ID =
            UUID.fromString("2e3a5870-1d18-45bb-9fd7-658de8f04d98");
    private static final UUID CHILD_ID =
            UUID.fromString("282446e1-4970-4dca-a0a6-45a00819915f");

    @Test
    void validatesPolicyAndRequestBoundaries() {
        RagRetrievalPolicy policy = new RagRetrievalPolicy(3, true, 20, 5, 2_000);
        RagRetrievalRequest request = new RagRetrievalRequest(
                "repository", "如何实现检索", TaskComplexity.COMPLEX, policy);

        assertThat(request.policy()).isSameAs(policy);
        assertThatThrownBy(() -> new RagRetrievalPolicy(0, false, 20, 5, 2_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rewriteLimit 必须在 1 到 3 之间");
        assertThatThrownBy(() -> new RagRetrievalPolicy(4, false, 20, 5, 2_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rewriteLimit 必须在 1 到 3 之间");
        assertThatThrownBy(() -> new RagRetrievalPolicy(1, false, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retrievalLimit 必须在 1 到 100 之间");
        assertThatThrownBy(() -> new RagRetrievalPolicy(1, false, 2, 3, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rerankLimit 不能超过 retrievalLimit");
        assertThatThrownBy(() -> new RagRetrievalRequest(
                " ", "query", TaskComplexity.SIMPLE, policy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("repositoryId 不能为空");
    }

    @Test
    void exposesOnlyExactStagesAndStatuses() {
        assertThat(RagStage.values()).containsExactly(
                RagStage.QUERY_REWRITE,
                RagStage.HYDE,
                RagStage.BASELINE_RETRIEVAL,
                RagStage.FUSION,
                RagStage.RERANK,
                RagStage.TOKEN_BUDGET);
        assertThat(RagStageStatus.values()).containsExactly(
                RagStageStatus.APPLIED,
                RagStageStatus.SKIPPED,
                RagStageStatus.DEGRADED);
    }

    @Test
    void freezesPipelineCollectionsAndChecksDegradedConsistency() {
        RagContextDocument document = document();
        RagStageEvidence degraded = new RagStageEvidence(
                RagStage.RERANK,
                RagStageStatus.DEGRADED,
                2,
                2,
                40,
                "rerank 失败后保留融合排序",
                "java.lang.IllegalStateException: rerank failed");
        List<RagContextDocument> documents = new ArrayList<>(List.of(document));
        List<RagStageEvidence> evidence = new ArrayList<>(List.of(degraded));

        RagRetrievalResult result = new RagRetrievalResult(documents, evidence, 40, true);
        documents.clear();
        evidence.clear();

        assertThat(result.documents()).containsExactly(document);
        assertThat(result.evidence()).containsExactly(degraded);
        assertThatThrownBy(() -> result.documents().add(document))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RagRetrievalResult(
                List.of(document), List.of(degraded), 40, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("degraded 与阶段证据不一致");
        assertThatThrownBy(() -> new RagRetrievalResult(
                List.of(document), List.of(), 39, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("estimatedTokens 必须等于文档 token 总数");
    }

    @Test
    void validatesScoresEvidenceAndBudgetFailure() {
        RagHit hit = hit();
        assertThat(new FusedHit(hit, 0.25).score()).isEqualTo(0.25);
        assertThat(new RerankedHit(CHILD_ID, 0.75).score()).isEqualTo(0.75);
        assertThatThrownBy(() -> new FusedHit(hit, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("score 必须是有限非负数");
        assertThatThrownBy(() -> new RagStageEvidence(
                RagStage.HYDE, RagStageStatus.APPLIED, 1, 1, 5, "ok", "stack"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("非降级阶段不能包含 errorStack");

        RagContextBudgetExceededException exception =
                new RagContextBudgetExceededException(101, 100);
        assertThat(exception.estimatedTokens()).isEqualTo(101);
        assertThat(exception.limit()).isEqualTo(100);
    }

    @Test
    void rejectsDocumentTokenSumOverflow() {
        RagContextDocument maximum = document(Integer.MAX_VALUE);

        assertThatThrownBy(() -> new RagRetrievalResult(
                List.of(maximum, maximum), List.of(), -2, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("文档 token 总数超过整数上限");
    }

    private RagContextDocument document() {
        return document(40);
    }

    private RagContextDocument document(int estimatedTokens) {
        return new RagContextDocument(
                CHILD_ID,
                PARENT_ID,
                "src/App.java",
                "demo.App#run()",
                2,
                5,
                "void run() {}",
                RagContentSource.CHILD,
                0.25,
                0.75,
                estimatedTokens);
    }

    private RagHit hit() {
        ParentChunk parent = new ParentChunk(
                PARENT_ID,
                "repository",
                "src/App.java",
                "demo.App",
                "class App { void run() {} }",
                1,
                6,
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
                5,
                new float[8]);
        return new RagHit(child, parent, 0.4, 0.3, 1.0, 0.61);
    }
}
