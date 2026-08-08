package com.agent.rag.ingest;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.store.RagStore;
import com.agent.sandbox.ast.AstService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodebaseChunkerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void chunksJavaClassesDirectMethodsAndOverloads() throws IOException {
        Path source = write("src/main/java/demo/Outer.java", """
                package demo;

                public class Outer {
                    public void run() {
                    }

                    public void run(int value) {
                    }

                    class Inner {
                        void inner() {
                        }
                    }
                }

                final class Empty {
                }
                """);

        ChunkBatch batch = new CodebaseChunker(new AstService())
                .chunk(temporaryDirectory, "repository-1");

        assertThat(batch.parents())
                .extracting(ParentChunk::symbol)
                .containsExactly("demo.Outer", "demo.Outer.Inner", "demo.Empty");
        assertThat(batch.parents())
                .extracting(ParentChunk::path)
                .containsOnly("src/main/java/demo/Outer.java");
        assertThat(batch.parents())
                .extracting(ParentChunk::metadataJson)
                .containsOnly("{\"kind\":\"JAVA_CLASS\"}");

        ParentChunk outer = parent(batch, "demo.Outer");
        assertThat(children(batch, outer))
                .extracting(ChildDraft::symbol)
                .containsExactly(
                        "demo.Outer#public void run()",
                        "demo.Outer#public void run(int value)");
        assertThat(children(batch, outer))
                .extracting(ChildDraft::ordinal)
                .containsExactly(0, 1);
        assertThat(children(batch, outer))
                .extracting(ChildDraft::content)
                .allSatisfy(content -> assertThat(content).contains("run"));

        ParentChunk inner = parent(batch, "demo.Outer.Inner");
        assertThat(children(batch, inner))
                .extracting(ChildDraft::symbol)
                .containsExactly("demo.Outer.Inner# void inner()");

        ParentChunk empty = parent(batch, "demo.Empty");
        assertThat(children(batch, empty)).singleElement().satisfies(child -> {
            assertThat(child.symbol()).isEqualTo("demo.Empty");
            assertThat(child.content()).isEqualTo(empty.content());
            assertThat(child.startLine()).isEqualTo(empty.startLine());
            assertThat(child.endLine()).isEqualTo(empty.endLine());
        });
        assertThat(source).exists();
    }

    @Test
    void chunksTextInOverlappingWindowsAndSkipsExcludedOrBinaryFiles() throws IOException {
        List<String> lines = new ArrayList<>();
        for (int line = 1; line <= 141; line++) {
            lines.add("line-" + line);
        }
        write("README.md", String.join("\n", lines));
        write("target/ignored.txt", "target");
        write("node_modules/ignored.txt", "node");
        write(".git/ignored.txt", "git");
        Files.write(temporaryDirectory.resolve("binary.bin"), new byte[]{1, 0, 2});
        Files.write(temporaryDirectory.resolve("invalid.txt"), new byte[]{(byte) 0xC3, 0x28});

        ChunkBatch batch = new CodebaseChunker(new AstService())
                .chunk(temporaryDirectory, "repository-1");

        assertThat(batch.parents()).singleElement().satisfies(parent -> {
            assertThat(parent.path()).isEqualTo("README.md");
            assertThat(parent.symbol()).isNull();
            assertThat(parent.startLine()).isEqualTo(1);
            assertThat(parent.endLine()).isEqualTo(141);
            assertThat(parent.metadataJson()).isEqualTo("{\"kind\":\"TEXT_FILE\"}");
        });
        assertThat(batch.children()).hasSize(2);
        assertThat(batch.children().get(0).startLine()).isEqualTo(1);
        assertThat(batch.children().get(0).endLine()).isEqualTo(120);
        assertThat(batch.children().get(0).content())
                .startsWith("line-1\n")
                .endsWith("line-120");
        assertThat(batch.children().get(1).startLine()).isEqualTo(101);
        assertThat(batch.children().get(1).endLine()).isEqualTo(141);
        assertThat(batch.children().get(1).content())
                .startsWith("line-101\n")
                .endsWith("line-141");
    }

    @Test
    void ingestsEightDimensionalEmbeddingsAndReplacesRepository() throws IOException {
        write("notes.txt", "alpha\nbeta");
        RecordingStore store = new RecordingStore();
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return new float[]{text.length(), 2, 3, 4, 5, 6, 7, 8};
            }
        };

        new CodebaseIngestionService(new AstService(), model, store)
                .ingest(temporaryDirectory, "repository-1");

        assertThat(store.repositoryId).isEqualTo("repository-1");
        assertThat(store.parents).hasSize(1);
        assertThat(store.children).singleElement().satisfies(child ->
                assertThat(child.embedding()).containsExactly(10, 2, 3, 4, 5, 6, 7, 8));
    }

    @Test
    void ingestsCapturedSnapshotAndReturnsRepositoryIndex() throws IOException {
        write("notes.txt", "alpha\nbeta");
        RecordingStore store = new RecordingStore();
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return new float[8];
            }
        };
        RepositorySnapshot snapshot = new RepositorySourceScanner().capture(temporaryDirectory);

        com.agent.rag.store.RagRepositoryIndex index =
                new CodebaseIngestionService(new AstService(), model, store)
                        .ingest(snapshot, "repository-1");

        assertThat(index.repositoryId()).isEqualTo("repository-1");
        assertThat(index.workspaceFingerprint()).isEqualTo(snapshot.fingerprint());
        assertThat(index.parentCount()).isEqualTo(store.parents.size());
        assertThat(index.childCount()).isEqualTo(store.children.size());
        assertThat(store.index).isEqualTo(index);
    }

    @Test
    void rejectsWrongEmbeddingDimensionBeforeStoreWrite() throws IOException {
        write("notes.txt", "alpha");
        RecordingStore store = new RecordingStore();
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public int dimensions() {
                return 8;
            }

            @Override
            public float[] embed(String text) {
                return new float[7];
            }
        };

        CodebaseIngestionService service =
                new CodebaseIngestionService(new AstService(), model, store);

        assertThatThrownBy(() -> service.ingest(temporaryDirectory, "repository-1"))
                .isInstanceOf(CodebaseIngestionException.class)
                .hasMessage("embedding 维度必须为 8");
        assertThat(store.repositoryId).isNull();
    }

    @Test
    void validatesRepositoryRootAndIdentifier() {
        CodebaseChunker chunker = new CodebaseChunker(new AstService());

        assertThatThrownBy(() -> chunker.chunk(temporaryDirectory, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("repositoryId 不能为空");
        assertThatThrownBy(() -> chunker.chunk(
                temporaryDirectory.resolve("missing"), "repository-1"))
                .isInstanceOf(CodebaseIngestionException.class)
                .hasMessageContaining("仓库根目录无效")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void chunksOnlyCapturedJavaSourceAfterWorkspaceChanges() throws IOException {
        Path source = write("src/main/java/demo/Snapshot.java", """
                package demo;

                final class Snapshot {
                    void before() {
                    }
                }
                """);
        RepositorySnapshot snapshot = new RepositorySourceScanner()
                .capture(temporaryDirectory);
        Files.writeString(source, "broken java", StandardCharsets.UTF_8);

        ChunkBatch batch = new CodebaseChunker(new AstService())
                .chunk(snapshot, "repository-1");

        assertThat(batch.parents()).singleElement().satisfies(parent -> {
            assertThat(parent.symbol()).isEqualTo("demo.Snapshot");
            assertThat(parent.content()).contains("void before()");
        });
        assertThat(batch.children()).singleElement().satisfies(child ->
                assertThat(child.content()).contains("void before()"));
    }

    private Path write(String relativePath, String content) throws IOException {
        Path path = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private ParentChunk parent(ChunkBatch batch, String symbol) {
        return batch.parents().stream()
                .filter(parent -> symbol.equals(parent.symbol()))
                .findFirst()
                .orElseThrow();
    }

    private List<ChildDraft> children(ChunkBatch batch, ParentChunk parent) {
        return batch.children().stream()
                .filter(child -> child.parentId().equals(parent.parentId()))
                .toList();
    }

    private static final class RecordingStore implements RagStore {
        private String repositoryId;
        private List<ParentChunk> parents = List.of();
        private List<ChildChunk> children = List.of();
        private com.agent.rag.store.RagRepositoryIndex index;

        @Override
        public void replaceRepository(
                String repositoryId,
                List<ParentChunk> parents,
                List<ChildChunk> children) {
            this.repositoryId = repositoryId;
            this.parents = List.copyOf(parents);
            this.children = List.copyOf(children);
        }

        @Override
        public void replaceRepository(
                String repositoryId,
                List<ParentChunk> parents,
                List<ChildChunk> children,
                com.agent.rag.store.RagRepositoryIndex index) {
            replaceRepository(repositoryId, parents, children);
            this.index = index;
        }

        @Override
        public List<com.agent.rag.store.RetrievalRow> findByVector(
                String repositoryId, float[] queryEmbedding, int limit) {
            return List.of();
        }

        @Override
        public List<com.agent.rag.store.RetrievalRow> findByLexical(
                String repositoryId, String query, int limit) {
            return List.of();
        }

        @Override
        public long countChildren(String repositoryId) {
            return 0;
        }

        @Override
        public double averageDocumentLength(String repositoryId) {
            return 0;
        }

        @Override
        public Map<String, Long> documentFrequencies(
                String repositoryId, List<String> terms) {
            return Map.of();
        }
    }
}
