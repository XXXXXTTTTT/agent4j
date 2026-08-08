package com.agent.rag.index;

import com.agent.rag.ingest.CodebaseIngestionService;
import com.agent.rag.ingest.RepositorySnapshot;
import com.agent.rag.ingest.RepositorySourceScanner;
import com.agent.rag.store.RagRepositoryIndex;
import com.agent.rag.store.RagStore;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 以仓库为粒度协调按需索引，并合并同仓库的并发请求。 */
public final class CodebaseIndexCoordinator implements AutoCloseable {

    private final RepositorySourceScanner sourceScanner;
    private final CodebaseIngestionService ingestionService;
    private final RagStore ragStore;
    private final ExecutorService executor;
    private final ConcurrentMap<String, CompletableFuture<RagRepositoryIndex>> inFlight =
            new ConcurrentHashMap<>();

    /** 创建使用 Java 21 虚拟线程执行扫描与向量化的协调器。 */
    public CodebaseIndexCoordinator(
            RepositorySourceScanner sourceScanner,
            CodebaseIngestionService ingestionService,
            RagStore ragStore) {
        this.sourceScanner = Objects.requireNonNull(sourceScanner, "sourceScanner 不能为空");
        this.ingestionService = Objects.requireNonNull(
                ingestionService, "ingestionService 不能为空");
        this.ragStore = Objects.requireNonNull(ragStore, "ragStore 不能为空");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 确保仓库当前内容已建立索引；同 repositoryId 并发调用共享一个 Future。 */
    public CompletableFuture<RagRepositoryIndex> ensureIndexed(
            Path repositoryRoot,
            String repositoryId) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot 不能为空");
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId 不能为空");
        }
        CreatedFuture created = new CreatedFuture();
        CompletableFuture<RagRepositoryIndex> future = inFlight.compute(
                repositoryId,
                (key, current) -> {
                    if (current != null && !current.isDone()) {
                        return current;
                    }
                    CompletableFuture<RagRepositoryIndex> next = new CompletableFuture<>();
                    created.value = next;
                    return next;
                });
        if (created.value == future) {
            executor.execute(() -> index(repositoryRoot, repositoryId, future));
        }
        return future;
    }

    private void index(
            Path repositoryRoot,
            String repositoryId,
            CompletableFuture<RagRepositoryIndex> future) {
        try {
            RepositorySnapshot snapshot = sourceScanner.capture(repositoryRoot);
            RagRepositoryIndex persisted = ragStore.findRepositoryIndex(repositoryId)
                    .orElse(null);
            if (persisted != null
                    && persisted.workspaceFingerprint().equals(snapshot.fingerprint())) {
                future.complete(persisted);
            } else {
                future.complete(ingestionService.ingest(snapshot, repositoryId));
            }
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        } finally {
            inFlight.remove(repositoryId, future);
        }
    }

    /** 中断未完成的后台索引并关闭虚拟线程执行器。 */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class CreatedFuture {
        private CompletableFuture<RagRepositoryIndex> value;
    }
}
