package com.agent.rag.pipeline;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReciprocalRankFusionTest {

    private static final UUID CHILD_A =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_B =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CHILD_C =
            UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    @Test
    void accumulatesScoresAcrossRankedListsWithOneBasedRanks() {
        RagHit hitA = hit(CHILD_A, "src/A.java", 0, "alpha");
        RagHit hitB = hit(CHILD_B, "src/B.java", 0, "beta");
        RagHit hitC = hit(CHILD_C, "src/C.java", 0, "gamma");

        List<FusedHit> result = fusion.fuse(List.of(
                List.of(hitA, hitB, hitC),
                List.of(hitB, hitA),
                List.of(hitB, hitC)));

        assertThat(result).extracting(item -> item.hit().childChunk().childId())
                .containsExactly(CHILD_B, CHILD_A, CHILD_C);
        assertThat(result.get(0).score())
                .isCloseTo(1.0 / 62 + 1.0 / 61 + 1.0 / 61, within(1.0e-12));
        assertThat(result.get(1).score())
                .isCloseTo(1.0 / 61 + 1.0 / 62, within(1.0e-12));
        assertThat(result.get(2).score())
                .isCloseTo(1.0 / 63 + 1.0 / 62, within(1.0e-12));
    }

    @Test
    void preservesSingleListRankingAndReturnsFrozenEmptyResults() {
        RagHit hitA = hit(CHILD_A, "src/A.java", 0, "alpha");
        RagHit hitB = hit(CHILD_B, "src/B.java", 0, "beta");

        List<FusedHit> result = fusion.fuse(List.of(List.of(hitB, hitA)));

        assertThat(result).extracting(item -> item.hit().childChunk().childId())
                .containsExactly(CHILD_B, CHILD_A);
        assertThat(fusion.fuse(List.of())).isEmpty();
        assertThat(fusion.fuse(List.of(List.of()))).isEmpty();
        assertThatThrownBy(() -> result.add(result.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ordersEqualScoresByPathOrdinalAndChildId() {
        UUID smallerId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID largerId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        RagHit zPath = hit(CHILD_A, "src/Z.java", 0, "z");
        RagHit laterOrdinal = hit(CHILD_B, "src/A.java", 1, "later");
        RagHit largerChildId = hit(largerId, "src/A.java", 0, "larger");
        RagHit smallerChildId = hit(smallerId, "src/A.java", 0, "smaller");

        List<FusedHit> result = fusion.fuse(List.of(
                List.of(zPath),
                List.of(laterOrdinal),
                List.of(largerChildId),
                List.of(smallerChildId)));

        assertThat(result).extracting(item -> item.hit().childChunk().childId())
                .containsExactly(smallerId, largerId, CHILD_B, CHILD_A);
    }

    @Test
    void rejectsConflictingContentForTheSameChildId() {
        RagHit original = hit(CHILD_A, "src/A.java", 0, "alpha");
        RagHit conflicting = hit(CHILD_A, "src/A.java", 0, "changed");

        assertThatThrownBy(() -> fusion.fuse(List.of(
                List.of(original), List.of(conflicting))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("同一 childId 的融合命中内容不一致");
    }

    private RagHit hit(UUID childId, String path, int ordinal, String content) {
        UUID parentId = UUID.nameUUIDFromBytes((childId + "-parent").getBytes());
        ParentChunk parent = new ParentChunk(
                parentId,
                "repository",
                path,
                "demo.Symbol",
                "class Symbol { " + content + " }",
                1,
                3,
                "{}");
        ChildChunk child = new ChildChunk(
                childId,
                parentId,
                "repository",
                path,
                "demo.Symbol#method()",
                ordinal,
                content,
                2,
                2,
                new float[8]);
        return new RagHit(child, parent, 0.4, 0.3, 0.2, 0.34);
    }
}
