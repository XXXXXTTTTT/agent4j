package com.agent.rag.index;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.ingest.CodebaseIngestionService;
import com.agent.rag.ingest.RepositorySourceScanner;
import com.agent.rag.store.RagRepositoryIndex;
import com.agent.rag.store.RagStore;
import com.agent.rag.store.RetrievalRow;
import com.agent.sandbox.ast.AstService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodebaseIndexCoordinatorTest {

    @TempDir
    Path workspace;

    @Test
    void sharesOneInFlightFutureAndEmbedsOnlyOncePerRepository() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "single-flight", StandardCharsets.UTF_8);
        BlockingEmbeddingModel model = new BlockingEmbeddingModel();
        RecordingStore store = new RecordingStore();
        CodebaseIndexCoordinator coordinator = coordinator(model, store);
        try {
            CompletableFuture<RagRepositoryIndex> first =
                    coordinator.ensureIndexed(workspace, "repo-a");
            assertThat(model.started.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<RagRepositoryIndex> second =
                    coordinator.ensureIndexed(workspace, "repo-a");

            assertThat(second).isSameAs(first);
            model.release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).childCount()).isEqualTo(1);
            assertThat(model.calls.get()).isEqualTo(1);
            assertThat(model.virtualThread).isTrue();
            assertThat(store.replacements.get()).isEqualTo(1);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void skipsIngestWhenPersistedFingerprintMatches() throws IOException {
        Files.writeString(workspace.resolve("README.md"), "unchanged", StandardCharsets.UTF_8);
        RepositorySourceScanner scanner = new RepositorySourceScanner();
        String fingerprint = scanner.capture(workspace).fingerprint();
        RecordingStore store = new RecordingStore();
        store.index = new RagRepositoryIndex("repo-a", fingerprint, 1, 1, Instant.now());
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        CodebaseIndexCoordinator coordinator = coordinator(model, store);
        try {
            assertThat(coordinator.ensureIndexed(workspace, "repo-a").join())
                    .isEqualTo(store.index);
            assertThat(model.calls.get()).isZero();
            assertThat(store.replacements.get()).isZero();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void removesFailedFutureSoNextRequestCanRetry() throws IOException {
        Files.writeString(workspace.resolve("README.md"), "retry", StandardCharsets.UTF_8);
        FailingOnceEmbeddingModel model = new FailingOnceEmbeddingModel();
        RecordingStore store = new RecordingStore();
        RagRepositoryIndex oldIndex = new RagRepositoryIndex(
                "repo-a", "a".repeat(64), 1, 1, Instant.now());
        store.index = oldIndex;
        CodebaseIndexCoordinator coordinator = coordinator(model, store);
        try {
            CompletableFuture<RagRepositoryIndex> failed =
                    coordinator.ensureIndexed(workspace, "repo-a");
            assertThatThrownBy(failed::join)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(store.index).isEqualTo(oldIndex);
            assertThat(coordinator.ensureIndexed(workspace, "repo-a").join().childCount())
                    .isEqualTo(1);
            assertThat(model.calls.get()).isEqualTo(2);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void removesCompletedFutureBeforeRunningDependentCallbacks() throws Exception {
        Path source = workspace.resolve("README.md");
        Files.writeString(source, "before", StandardCharsets.UTF_8);
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RecordingStore store = new RecordingStore();
        CodebaseIndexCoordinator coordinator = coordinator(model, store);
        try {
            CompletableFuture<RagRepositoryIndex> second =
                    coordinator.ensureIndexed(workspace, "repo-a")
                            .thenCompose(firstIndex -> {
                                try {
                                    Files.writeString(source, "after", StandardCharsets.UTF_8);
                                } catch (IOException exception) {
                                    throw new UncheckedIOException(exception);
                                }
                                return coordinator.ensureIndexed(workspace, "repo-a");
                            });

            assertThat(second.get(5, TimeUnit.SECONDS).workspaceFingerprint())
                    .isEqualTo(new RepositorySourceScanner().capture(workspace).fingerprint());
            assertThat(model.calls.get()).isEqualTo(2);
            assertThat(store.replacements.get()).isEqualTo(2);
        } finally {
            coordinator.close();
        }
    }

    private CodebaseIndexCoordinator coordinator(EmbeddingModel model, RecordingStore store) {
        return new CodebaseIndexCoordinator(
                new RepositorySourceScanner(),
                new CodebaseIngestionService(new AstService(), model, store),
                store);
    }

    private static class CountingEmbeddingModel implements EmbeddingModel {
        protected final AtomicInteger calls = new AtomicInteger();
        protected volatile boolean virtualThread;

        @Override
        public int dimensions() {
            return 8;
        }

        @Override
        public float[] embed(String text) {
            virtualThread = Thread.currentThread().isVirtual();
            calls.incrementAndGet();
            return new float[ChildChunk.EMBEDDING_DIMENSIONS];
        }
    }

    private static final class BlockingEmbeddingModel extends CountingEmbeddingModel {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public float[] embed(String text) {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("embedding release timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("embedding interrupted", exception);
            }
            return super.embed(text);
        }
    }

    private static final class FailingOnceEmbeddingModel extends CountingEmbeddingModel {
        @Override
        public float[] embed(String text) {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("embedding failed once");
            }
            return new float[ChildChunk.EMBEDDING_DIMENSIONS];
        }
    }

    private static final class RecordingStore implements RagStore {
        private final AtomicInteger replacements = new AtomicInteger();
        private RagRepositoryIndex index;

        @Override
        public void replaceRepository(String repositoryId,
                List<com.agent.rag.domain.ParentChunk> parents,
                List<ChildChunk> children,
                RagRepositoryIndex index) {
            replacements.incrementAndGet();
            this.index = index;
        }

        @Override
        public Optional<RagRepositoryIndex> findRepositoryIndex(String repositoryId) {
            return Optional.ofNullable(index);
        }

        @Override public void replaceRepository(String id,
                List<com.agent.rag.domain.ParentChunk> parents,
                List<ChildChunk> children) { }
        @Override public List<RetrievalRow> findByVector(String id, float[] embedding, int limit) { return List.of(); }
        @Override public List<RetrievalRow> findByLexical(String id, String query, int limit) { return List.of(); }
        @Override public long countChildren(String id) { return 0; }
        @Override public double averageDocumentLength(String id) { return 0; }
        @Override public Map<String, Long> documentFrequencies(String id, List<String> terms) { return Map.of(); }
    }
}
