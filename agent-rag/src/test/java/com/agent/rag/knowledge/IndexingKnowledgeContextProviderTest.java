package com.agent.rag.knowledge;

import com.agent.core.intent.TaskComplexity;
import com.agent.core.knowledge.KnowledgeContext;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.core.knowledge.KnowledgeContextRequest;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.index.CodebaseIndexCoordinator;
import com.agent.rag.ingest.CodebaseIngestionService;
import com.agent.rag.ingest.RepositorySourceScanner;
import com.agent.rag.store.RagRepositoryIndex;
import com.agent.rag.store.RagStore;
import com.agent.rag.store.RetrievalRow;
import com.agent.sandbox.ast.AstService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexingKnowledgeContextProviderTest {

    @TempDir
    Path workspace;

    @Test
    void waitsForIndexBeforeDelegatingKnowledgeLoad() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "rules");
        BlockingEmbeddingModel model = new BlockingEmbeddingModel();
        CodebaseIndexCoordinator coordinator = coordinator(model);
        KnowledgeContext expected = new KnowledgeContext(
                "answer", 1, "fingerprint", 1, false, List.of());
        AtomicInteger delegateCalls = new AtomicInteger();
        KnowledgeContextProvider delegate = request -> {
            delegateCalls.incrementAndGet();
            return expected;
        };
        IndexingKnowledgeContextProvider provider =
                new IndexingKnowledgeContextProvider(coordinator, delegate, Duration.ofSeconds(2));
        try {
            java.util.concurrent.CompletableFuture<KnowledgeContext> loading =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> provider.load(request()));
            assertThat(model.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(delegateCalls.get()).isZero();
            model.release.countDown();
            assertThat(loading.get(5, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(delegateCalls.get()).isEqualTo(1);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void preservesTimeoutCause() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "rules");
        BlockingEmbeddingModel model = new BlockingEmbeddingModel();
        CodebaseIndexCoordinator coordinator = coordinator(model);
        IndexingKnowledgeContextProvider provider = new IndexingKnowledgeContextProvider(
                coordinator, request -> KnowledgeContext.empty(),
                Duration.ofMillis(1));
        try {
            assertThatThrownBy(() -> provider.load(request()))
                    .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        } finally {
            model.release.countDown();
            coordinator.close();
        }
    }

    @Test
    void preservesIndexExecutionFailureCause() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "rules");
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public int dimensions() {
                return ChildChunk.EMBEDDING_DIMENSIONS;
            }

            @Override
            public float[] embed(String text) {
                throw new IllegalStateException("embedding unavailable");
            }
        };
        CodebaseIndexCoordinator coordinator = coordinator(model);
        IndexingKnowledgeContextProvider provider = new IndexingKnowledgeContextProvider(
                coordinator, request -> KnowledgeContext.empty(), Duration.ofSeconds(2));
        try {
            assertThatThrownBy(() -> provider.load(request()))
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .cause()
                    .hasMessage("embedding unavailable");
        } finally {
            coordinator.close();
        }
    }

    @Test
    void restoresInterruptFlagAndPreservesInterruptedCause() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "rules");
        BlockingEmbeddingModel model = new BlockingEmbeddingModel();
        CodebaseIndexCoordinator coordinator = coordinator(model);
        IndexingKnowledgeContextProvider provider = new IndexingKnowledgeContextProvider(
                coordinator, request -> KnowledgeContext.empty(), Duration.ofSeconds(5));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread waiting = Thread.ofPlatform().start(() -> {
            try {
                provider.load(request());
            } catch (Throwable throwable) {
                failure.set(throwable);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertThat(model.started.await(5, TimeUnit.SECONDS)).isTrue();
            waiting.interrupt();
            waiting.join(5_000);

            assertThat(waiting.isAlive()).isFalse();
            assertThat(interrupted.get()).isTrue();
            assertThat(failure.get()).hasCauseInstanceOf(InterruptedException.class);
        } finally {
            model.release.countDown();
            coordinator.close();
        }
    }

    private KnowledgeContextRequest request() {
        return new KnowledgeContextRequest(
                "repo-a", "user-a", workspace, workspace,
                "query", TaskComplexity.SIMPLE, 100);
    }

    private CodebaseIndexCoordinator coordinator(EmbeddingModel model) {
        RecordingStore store = new RecordingStore();
        return new CodebaseIndexCoordinator(
                new RepositorySourceScanner(),
                new CodebaseIngestionService(new AstService(), model, store),
                store);
    }

    private static final class BlockingEmbeddingModel implements EmbeddingModel {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public int dimensions() {
            return ChildChunk.EMBEDDING_DIMENSIONS;
        }

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
            return new float[ChildChunk.EMBEDDING_DIMENSIONS];
        }
    }

    private static final class RecordingStore implements RagStore {
        private RagRepositoryIndex index;

        @Override
        public void replaceRepository(String repositoryId,
                List<com.agent.rag.domain.ParentChunk> parents,
                List<ChildChunk> children,
                RagRepositoryIndex index) {
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
