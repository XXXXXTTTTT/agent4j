package com.agent.rag.search;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.domain.RagHit;
import com.agent.rag.domain.RagQuery;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.store.RagStore;
import com.agent.rag.store.RagStoreException;
import com.agent.rag.store.RetrievalRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridRagRetrieverTest {

    private static final UUID PARENT_A =
            UUID.fromString("f1dcac92-8f26-4fe6-af5d-a3e2c7ec2fcb");
    private static final UUID PARENT_B =
            UUID.fromString("6d82164c-f67a-4cbb-8c22-145122baf5ae");
    private static final UUID CHILD_A =
            UUID.fromString("0a216084-0d37-44f0-9410-7c39f6748a7a");
    private static final UUID CHILD_B =
            UUID.fromString("be790746-e0b4-4aeb-a7f9-5ed8eaf8aa65");

    @Test
    void combinesNormalizedVectorBm25AndExactSymbolScores() {
        ParentChunk parentA = parent(PARENT_A, "src/A.java", "demo.A");
        ParentChunk parentB = parent(PARENT_B, "src/B.java", "demo.B");
        ChildChunk childA = child(CHILD_A, PARENT_A, "demo.A#alpha()", "alpha");
        ChildChunk childB = child(CHILD_B, PARENT_B, "demo.B#beta()", "beta");
        InMemoryStore store = new InMemoryStore(
                List.of(new RetrievalRow(childA, parentA, 0.9),
                        new RetrievalRow(childB, parentB, 0.1)),
                List.of(new RetrievalRow(childA, parentA, 1),
                        new RetrievalRow(childB, parentB, 1)),
                2,
                1,
                Map.of("alpha", 1L, "src", 0L));
        HybridRagRetriever retriever = new HybridRagRetriever(store, model());

        List<RagHit> hits = retriever.search(
                new RagQuery("repo", "alpha demo.A", new float[8], 10));

        assertThat(hits).extracting(hit -> hit.childChunk().childId())
                .containsExactly(CHILD_A, CHILD_B);
        assertThat(hits.getFirst().vectorScore()).isEqualTo(1.0);
        assertThat(hits.getFirst().bm25Score()).isEqualTo(1.0);
        assertThat(hits.getFirst().symbolScore()).isEqualTo(1.0);
        assertThat(hits.getFirst().finalScore()).isEqualTo(1.0);
        assertThat(hits.get(1).finalScore()).isZero();
    }

    @Test
    void implementsTheBaseRetrievalPort() {
        RagRetriever retriever = new HybridRagRetriever(
                new InMemoryStore(List.of(), List.of(), 0, 0, Map.of()),
                model());

        assertThat(retriever.search(
                new RagQuery("repo", "query", new float[8], 10))).isEmpty();
    }

    @Test
    void usesStablePathOrdinalAndChildIdTieOrdering() {
        ParentChunk parentA = parent(PARENT_A, "src/A.java", null);
        ParentChunk parentB = parent(PARENT_B, "src/B.java", null);
        ChildChunk childA = child(CHILD_A, PARENT_A, null, "one");
        ChildChunk childB = child(CHILD_B, PARENT_B, null, "two");
        InMemoryStore store = new InMemoryStore(
                List.of(new RetrievalRow(childB, parentB, 0.5),
                        new RetrievalRow(childA, parentA, 0.5)),
                List.of(), 2, 1, Map.of());

        List<RagHit> hits = new HybridRagRetriever(store, model()).search(
                new RagQuery("repo", "unmatched", new float[8], 1));

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().childChunk().childId()).isEqualTo(CHILD_A);
    }

    @Test
    void computesMissingQueryEmbeddingAndRejectsForeignRepositoryRows() {
        ParentChunk parent = parent(PARENT_A, "src/A.java", "demo.A");
        ChildChunk child = child(CHILD_A, PARENT_A, "demo.A#run()", "run");
        InMemoryStore store = new InMemoryStore(
                List.of(new RetrievalRow(child, parent, 1)),
                List.of(), 1, 1, Map.of());
        CountingModel model = new CountingModel();

        List<RagHit> hits = new HybridRagRetriever(store, model).search(
                new RagQuery("repo", "run", null, 10));
        assertThat(hits).hasSize(1);
        assertThat(model.calls).isEqualTo(1);

        ParentChunk foreignParent = parentFor(
                "foreign", PARENT_B, "src/B.java", "demo.B");
        ChildChunk foreignChild = childFor(
                "foreign", CHILD_B, PARENT_B, "demo.B#run()", "run");
        InMemoryStore foreignStore = new InMemoryStore(
                List.of(new RetrievalRow(foreignChild, foreignParent, 1)),
                List.of(), 1, 1, Map.of());
        assertThatThrownBy(() -> new HybridRagRetriever(foreignStore, model)
                .search(new RagQuery("repo", "run", new float[8], 10)))
                .isInstanceOf(RagStoreException.class)
                .hasMessage("召回行 repositoryId 不一致");
    }

    private EmbeddingModel model() {
        return new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return new float[8];
            }
        };
    }

    private ParentChunk parent(UUID id, String path, String symbol) {
        return parentFor("repo", id, path, symbol);
    }

    private ParentChunk parentFor(
            String repositoryId, UUID id, String path, String symbol) {
        return new ParentChunk(id, repositoryId, path, symbol,
                "class Content {}", 1, 1, "{}");
    }

    private ChildChunk child(UUID id, UUID parentId, String symbol, String content) {
        return childFor("repo", id, parentId, symbol, content);
    }

    private ChildChunk childFor(
            String repositoryId,
            UUID id,
            UUID parentId,
            String symbol,
            String content) {
        String path = parentId.equals(PARENT_A) ? "src/A.java" : "src/B.java";
        return new ChildChunk(id, parentId, repositoryId, path,
                symbol, 0, content, 1, 1, new float[8]);
    }

    private static final class CountingModel implements EmbeddingModel {
        private int calls;

        @Override
        public int dimensions() {
            return 8;
        }

        @Override
        public float[] embed(String text) {
            calls++;
            return new float[8];
        }
    }

    private static final class InMemoryStore implements RagStore {
        private final List<RetrievalRow> vectorRows;
        private final List<RetrievalRow> lexicalRows;
        private final long corpusSize;
        private final double averageLength;
        private final Map<String, Long> frequencies;

        private InMemoryStore(
                List<RetrievalRow> vectorRows,
                List<RetrievalRow> lexicalRows,
                long corpusSize,
                double averageLength,
                Map<String, Long> frequencies) {
            this.vectorRows = vectorRows;
            this.lexicalRows = lexicalRows;
            this.corpusSize = corpusSize;
            this.averageLength = averageLength;
            this.frequencies = frequencies;
        }

        @Override
        public void replaceRepository(
                String repositoryId,
                List<ParentChunk> parents,
                List<ChildChunk> children) {
        }

        @Override
        public List<RetrievalRow> findByVector(
                String repositoryId, float[] queryEmbedding, int limit) {
            return vectorRows;
        }

        @Override
        public List<RetrievalRow> findByLexical(
                String repositoryId, String query, int limit) {
            return lexicalRows;
        }

        @Override
        public long countChildren(String repositoryId) {
            return corpusSize;
        }

        @Override
        public double averageDocumentLength(String repositoryId) {
            return averageLength;
        }

        @Override
        public Map<String, Long> documentFrequencies(
                String repositoryId, List<String> terms) {
            return frequencies;
        }
    }
}
