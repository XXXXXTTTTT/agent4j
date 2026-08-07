package com.agent.rag.pipeline;

import com.agent.core.context.TokenEstimator;
import com.agent.core.llm.ChatMessage;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagTokenBudgetSelectorTest {

    private static final UUID PARENT_A =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PARENT_B =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID CHILD_A =
            UUID.fromString("21000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_B =
            UUID.fromString("21000000-0000-0000-0000-000000000002");
    private static final UUID CHILD_C =
            UUID.fromString("21000000-0000-0000-0000-000000000003");

    @Test
    void injectsEachParentOnlyOnceWithParentMetadataAndScores() {
        ParentChunk parent = parent(
                PARENT_A, "src/Parent.java", "demo.Parent", "parent-a");
        FusedHit first = fusedHit(
                CHILD_A, parent, "demo.Parent#first()", 0, "child-a", 0.8);
        FusedHit second = fusedHit(
                CHILD_B, parent, "demo.Parent#second()", 1, "child-b", 0.7);
        RagTokenBudgetSelector selector = selector(Map.of(
                "parent-a", 4,
                "child-a", 2,
                "child-b", 2));

        List<RagContextDocument> result = selector.select(
                List.of(first, second),
                List.of(new RerankedHit(CHILD_A, 0.9),
                        new RerankedHit(CHILD_B, 0.85)),
                10);

        assertThat(result).hasSize(1);
        RagContextDocument document = result.getFirst();
        assertThat(document.childId()).isEqualTo(CHILD_A);
        assertThat(document.parentId()).isEqualTo(PARENT_A);
        assertThat(document.path()).isEqualTo("src/Parent.java");
        assertThat(document.symbol()).isEqualTo("demo.Parent");
        assertThat(document.startLine()).isEqualTo(1);
        assertThat(document.endLine()).isEqualTo(10);
        assertThat(document.content()).isEqualTo("parent-a");
        assertThat(document.contentSource()).isEqualTo(RagContentSource.PARENT);
        assertThat(document.retrievalScore()).isEqualTo(0.8);
        assertThat(document.rerankScore()).isEqualTo(0.9);
        assertThat(document.estimatedTokens()).isEqualTo(4);
        assertThatThrownBy(() -> result.add(document))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fallsBackToTheWholeChildWhenParentExceedsRemainingBudget() {
        ParentChunk firstParent = parent(
                PARENT_A, "src/A.java", "demo.A", "parent-six");
        ParentChunk secondParent = parent(
                PARENT_B, "src/B.java", "demo.B", "parent-five");
        FusedHit first = fusedHit(
                CHILD_A, firstParent, "demo.A#run()", 0, "child-a", 0.9);
        FusedHit second = fusedHit(
                CHILD_B, secondParent, "demo.B#run()", 2, "child-three", 0.8);
        RagTokenBudgetSelector selector = selector(Map.of(
                "parent-six", 6,
                "parent-five", 5,
                "child-a", 2,
                "child-three", 3));

        List<RagContextDocument> result = selector.select(
                List.of(first, second),
                List.of(new RerankedHit(CHILD_A, 1.0),
                        new RerankedHit(CHILD_B, 0.7)),
                10);

        assertThat(result).extracting(RagContextDocument::contentSource)
                .containsExactly(RagContentSource.PARENT, RagContentSource.CHILD);
        RagContextDocument childDocument = result.get(1);
        assertThat(childDocument.path()).isEqualTo("src/B.java");
        assertThat(childDocument.symbol()).isEqualTo("demo.B#run()");
        assertThat(childDocument.startLine()).isEqualTo(4);
        assertThat(childDocument.endLine()).isEqualTo(6);
        assertThat(childDocument.content()).isEqualTo("child-three");
        assertThat(childDocument.estimatedTokens()).isEqualTo(3);
        assertThat(result).extracting(RagContextDocument::estimatedTokens)
                .containsExactly(6, 3);
        assertThat(result.stream().mapToInt(RagContextDocument::estimatedTokens).sum())
                .isLessThanOrEqualTo(10);
    }

    @Test
    void skipsLaterDocumentsThatCannotFitWithoutTruncatingThem() {
        ParentChunk firstParent = parent(
                PARENT_A, "src/A.java", "demo.A", "parent-six");
        ParentChunk secondParent = parent(
                PARENT_B, "src/B.java", "demo.B", "parent-seven");
        FusedHit first = fusedHit(
                CHILD_A, firstParent, "demo.A#run()", 0, "child-a", 0.9);
        FusedHit second = fusedHit(
                CHILD_B, secondParent, "demo.B#run()", 0, "child-five", 0.8);
        RagTokenBudgetSelector selector = selector(Map.of(
                "parent-six", 6,
                "parent-seven", 7,
                "child-a", 2,
                "child-five", 5));

        List<RagContextDocument> result = selector.select(
                List.of(first, second),
                List.of(new RerankedHit(CHILD_A, 1.0),
                        new RerankedHit(CHILD_B, 0.7)),
                10);

        assertThat(result).extracting(RagContextDocument::content)
                .containsExactly("parent-six");
    }

    @Test
    void rejectsTheFirstEvidenceWhenEvenItsChildExceedsTheTotalBudget() {
        ParentChunk parent = parent(
                PARENT_A, "src/A.java", "demo.A", "parent-twenty");
        FusedHit hit = fusedHit(
                CHILD_A, parent, "demo.A#run()", 0, "child-eleven", 0.9);
        RagTokenBudgetSelector selector = selector(Map.of(
                "parent-twenty", 20,
                "child-eleven", 11));

        assertThatThrownBy(() -> selector.select(
                List.of(hit),
                List.of(new RerankedHit(CHILD_A, 1.0)),
                10))
                .isInstanceOfSatisfying(
                        RagContextBudgetExceededException.class,
                        exception -> {
                            assertThat(exception.estimatedTokens()).isEqualTo(11);
                            assertThat(exception.limit()).isEqualTo(10);
                        })
                .hasMessage("RAG 首条证据超过 token 预算: 11 > 10");
    }

    private RagTokenBudgetSelector selector(Map<String, Integer> tokensByContent) {
        TokenEstimator estimator = message -> {
            assertThat(message.role()).isEqualTo(ChatMessage.Role.USER);
            assertThat(message.content()).isInstanceOf(ChatMessage.TextContent.class);
            String text = ((ChatMessage.TextContent) message.content()).text();
            Integer tokens = tokensByContent.get(text);
            if (tokens == null) {
                throw new IllegalArgumentException("未配置正文 token: " + text);
            }
            return tokens;
        };
        return new RagTokenBudgetSelector(estimator);
    }

    private ParentChunk parent(
            UUID parentId, String path, String symbol, String content) {
        return new ParentChunk(
                parentId,
                "repository",
                path,
                symbol,
                content,
                1,
                10,
                "{}");
    }

    private FusedHit fusedHit(
            UUID childId,
            ParentChunk parent,
            String symbol,
            int ordinal,
            String content,
            double score) {
        ChildChunk child = new ChildChunk(
                childId,
                parent.parentId(),
                "repository",
                parent.path(),
                symbol,
                ordinal,
                content,
                4,
                6,
                new float[8]);
        return new FusedHit(new RagHit(child, parent, 0, 0, 0, 0), score);
    }
}
