package com.agent.rag.pipeline;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class LexicalCoverageRerankerTest {

    private static final UUID CHILD_A =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_B =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID CHILD_C =
            UUID.fromString("10000000-0000-0000-0000-000000000003");

    private final LexicalCoverageReranker reranker = new LexicalCoverageReranker();

    @Test
    void combinesQueryCoverageAndNormalizedRetrievalScore() {
        FusedHit complete = fusedHit(
                CHILD_A, "src/A.java", "demo.A#run()", 0,
                "alpha beta", "class A {}", 10);
        FusedHit partial = fusedHit(
                CHILD_B, "src/B.java", "demo.B#run()", 0,
                "alpha", "class B {}", 5);
        FusedHit absent = fusedHit(
                CHILD_C, "src/C.java", "demo.C#run()", 0,
                "gamma", "class C {}", 0);

        List<RerankedHit> result = reranker.rerank(
                "alpha beta", List.of(absent, partial, complete), 3);

        assertThat(result).extracting(RerankedHit::childId)
                .containsExactly(CHILD_A, CHILD_B, CHILD_C);
        assertThat(result.get(0).score()).isCloseTo(1.0, within(1.0e-12));
        assertThat(result.get(1).score()).isCloseTo(0.5, within(1.0e-12));
        assertThat(result.get(2).score()).isZero();
    }

    @Test
    void coversChineseAndEnglishTokensAcrossAllEvidenceFields() {
        FusedHit hit = fusedHit(
                CHILD_A,
                "src/Main.java",
                "demo.Symbol#run()",
                0,
                "实现 检索",
                "RAG pipeline",
                1);

        List<RerankedHit> result = reranker.rerank(
                "检索 RAG symbol java", List.of(hit), 1);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().score()).isCloseTo(0.7, within(1.0e-12));
    }

    @Test
    void excludesTheParentSymbolFromChildCoverage() {
        UUID parentId = UUID.nameUUIDFromBytes((CHILD_A + "-parent").getBytes());
        ParentChunk parent = new ParentChunk(
                parentId,
                "repository",
                "src/Main.java",
                "parent.OnlySymbol",
                "parent body",
                1,
                3,
                "{}");
        ChildChunk child = new ChildChunk(
                CHILD_A,
                parentId,
                "repository",
                "src/Main.java",
                null,
                0,
                "child body",
                2,
                2,
                new float[8]);
        FusedHit hit = new FusedHit(
                new RagHit(child, parent, 0, 0, 0, 0), 1);

        List<RerankedHit> result = reranker.rerank(
                "OnlySymbol", List.of(hit), 1);

        assertThat(result.getFirst().score()).isZero();
    }

    @Test
    void usesStableOrderingWhenCoverageAndRetrievalScoresTie() {
        UUID smallerId = UUID.fromString("10000000-0000-0000-0000-000000000010");
        UUID largerId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        FusedHit zPath = fusedHit(
                CHILD_A, "src/Z.java", null, 0, "none", "none", 1);
        FusedHit laterOrdinal = fusedHit(
                CHILD_B, "src/A.java", null, 1, "none", "none", 1);
        FusedHit largerChildId = fusedHit(
                largerId, "src/A.java", null, 0, "none", "none", 1);
        FusedHit smallerChildId = fusedHit(
                smallerId, "src/A.java", null, 0, "none", "none", 1);

        List<RerankedHit> result = reranker.rerank(
                "absent",
                List.of(zPath, laterOrdinal, largerChildId, smallerChildId),
                4);

        assertThat(result).extracting(RerankedHit::childId)
                .containsExactly(smallerId, largerId, CHILD_B, CHILD_A);
        assertThat(result).allMatch(item -> item.score() == 0);
    }

    @Test
    void validatesExternalRerankerResultsAndFreezesTheList() {
        FusedHit hitA = fusedHit(
                CHILD_A, "src/A.java", null, 0, "alpha", "parent", 1);
        FusedHit hitB = fusedHit(
                CHILD_B, "src/B.java", null, 0, "beta", "parent", 0.5);
        List<FusedHit> source = List.of(hitA, hitB);
        List<RerankedHit> external = new ArrayList<>(List.of(
                new RerankedHit(CHILD_B, 0.8),
                new RerankedHit(CHILD_A, 0.7)));

        List<RerankedHit> validated = RerankValidation.validate(source, external, 2);
        external.clear();

        assertThat(validated).extracting(RerankedHit::childId)
                .containsExactly(CHILD_B, CHILD_A);
        assertThatThrownBy(() -> validated.add(new RerankedHit(CHILD_A, 0.1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> RerankValidation.validate(
                source, List.of(new RerankedHit(CHILD_C, 0.5)), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rerank 返回未知 childId: " + CHILD_C);
        assertThatThrownBy(() -> RerankValidation.validate(
                source,
                List.of(new RerankedHit(CHILD_A, 0.5),
                        new RerankedHit(CHILD_A, 0.4)),
                2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rerank 返回重复 childId: " + CHILD_A);
        assertThatThrownBy(() -> RerankValidation.validate(
                source,
                List.of(new RerankedHit(CHILD_A, 0.5),
                        new RerankedHit(CHILD_B, 0.4)),
                1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rerank 返回数量超过 limit: 2 > 1");

        List<RerankedHit> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> RerankValidation.validate(source, withNull, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rerank 结果不能包含 null");
        assertThatThrownBy(() -> RerankValidation.validate(source, null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("rerankedHits 不能为空");
        assertThatThrownBy(() -> new RerankedHit(CHILD_A, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("score 必须是有限非负数");
    }

    private FusedHit fusedHit(
            UUID childId,
            String path,
            String symbol,
            int ordinal,
            String childContent,
            String parentContent,
            double score) {
        UUID parentId = UUID.nameUUIDFromBytes((childId + "-parent").getBytes());
        ParentChunk parent = new ParentChunk(
                parentId,
                "repository",
                path,
                symbol,
                parentContent,
                1,
                3,
                "{}");
        ChildChunk child = new ChildChunk(
                childId,
                parentId,
                "repository",
                path,
                symbol,
                ordinal,
                childContent,
                2,
                2,
                new float[8]);
        return new FusedHit(new RagHit(child, parent, 0, 0, 0, 0), score);
    }
}
