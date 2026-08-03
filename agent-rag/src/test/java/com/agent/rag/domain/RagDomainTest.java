package com.agent.rag.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagDomainTest {

    private static final UUID PARENT_ID =
            UUID.fromString("8686c3ab-8003-4ef7-9040-390a2a9e9c37");
    private static final UUID CHILD_ID =
            UUID.fromString("c9c06428-dfb7-47cb-bf48-17a69f9227ca");

    @Test
    void protectsEmbeddingArraysFromExternalMutation() {
        float[] embedding = {1, 2, 3, 4, 5, 6, 7, 8};
        ChildChunk chunk = childChunk(embedding);

        embedding[0] = 99;
        float[] returned = chunk.embedding();
        returned[1] = 99;

        assertThat(chunk.embedding()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    void rejectsInvalidChunkRangesAndEmbeddingDimensions() {
        assertThatThrownBy(() -> new ParentChunk(
                PARENT_ID, "repo", "src/App.java", "com.example.App",
                "class App {}", 0, 1, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源码行号范围无效");
        assertThatThrownBy(() -> childChunk(new float[7]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embedding 维度必须为 8");
    }

    @Test
    void validatesExactQueryProtocol() {
        assertThatThrownBy(() -> new RagQuery(" ", "query", null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("repositoryId 不能为空");
        assertThatThrownBy(() -> new RagQuery("repo", " ", null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("query 不能为空");
        assertThatThrownBy(() -> new RagQuery("repo", "query", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit 必须在 1 到 100 之间");
        assertThatThrownBy(() -> new RagQuery("repo", "query", null, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit 必须在 1 到 100 之间");
        assertThatThrownBy(() -> new RagQuery("repo", "query", new float[9], 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("queryEmbedding 维度必须为 8");
    }

    @Test
    void rejectsNonFiniteOrNegativeHitScores() {
        ParentChunk parent = parentChunk();
        ChildChunk child = childChunk(new float[8]);

        assertThatThrownBy(() -> new RagHit(
                child, parent, Double.NaN, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("vectorScore 必须是有限非负数");
        assertThatThrownBy(() -> new RagHit(
                child, parent, 0, 0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("symbolScore 必须是有限非负数");
    }

    private ParentChunk parentChunk() {
        return new ParentChunk(
                PARENT_ID,
                "repo",
                "src/App.java",
                "com.example.App",
                "class App {}",
                1,
                1,
                "{\"kind\":\"JAVA_CLASS\"}");
    }

    private ChildChunk childChunk(float[] embedding) {
        return new ChildChunk(
                CHILD_ID,
                PARENT_ID,
                "repo",
                "src/App.java",
                "com.example.App#run()",
                0,
                "void run() {}",
                1,
                1,
                embedding);
    }
}
