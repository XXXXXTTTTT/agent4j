package com.agent.rag.ingest;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.store.RagRepositoryIndex;
import com.agent.rag.store.RagStore;
import com.agent.sandbox.ast.AstService;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** 读取、切片、向量化并事务替换一个代码库索引。 */
public final class CodebaseIngestionService {

    private final CodebaseChunker chunker;
    private final RepositorySourceScanner sourceScanner;
    private final EmbeddingModel embeddingModel;
    private final RagStore ragStore;
    private final Clock clock;

    /** 创建 ingest 服务。 */
    public CodebaseIngestionService(
            AstService astService,
            EmbeddingModel embeddingModel,
            RagStore ragStore) {
        this(astService, embeddingModel, ragStore, Clock.systemUTC());
    }

    /** 创建使用指定时钟生成索引时间的 ingest 服务。 */
    public CodebaseIngestionService(
            AstService astService,
            EmbeddingModel embeddingModel,
            RagStore ragStore,
            Clock clock) {
        this.chunker = new CodebaseChunker(Objects.requireNonNull(astService, "astService 不能为空"));
        this.sourceScanner = new RepositorySourceScanner();
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel 不能为空");
        this.ragStore = Objects.requireNonNull(ragStore, "ragStore 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (embeddingModel.dimensions() != ChildChunk.EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("EmbeddingModel dimensions 必须为 8");
        }
    }

    /** 完成一次 repositoryId 的全量替换 ingest。 */
    public RagRepositoryIndex ingest(Path repositoryRoot, String repositoryId) {
        return ingest(sourceScanner.capture(repositoryRoot), repositoryId);
    }

    /** 仅使用指定不可变快照完成切片、向量化和原子替换。 */
    public RagRepositoryIndex ingest(RepositorySnapshot snapshot, String repositoryId) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        ChunkBatch batch = chunker.chunk(snapshot, repositoryId);
        List<ChildChunk> children = batch.children().stream()
                .map(this::embed)
                .toList();
        RagRepositoryIndex index = new RagRepositoryIndex(
                repositoryId,
                snapshot.fingerprint(),
                batch.parents().size(),
                children.size(),
                clock.instant());
        ragStore.replaceRepository(repositoryId, batch.parents(), children, index);
        return index;
    }

    private ChildChunk embed(ChildDraft draft) {
        float[] embedding = embeddingModel.embed(draft.content());
        if (embedding == null || embedding.length != ChildChunk.EMBEDDING_DIMENSIONS) {
            throw new CodebaseIngestionException("embedding 维度必须为 8");
        }
        return new ChildChunk(
                draft.childId(),
                draft.parentId(),
                draft.repositoryId(),
                draft.path(),
                draft.symbol(),
                draft.ordinal(),
                draft.content(),
                draft.startLine(),
                draft.endLine(),
                embedding);
    }
}
