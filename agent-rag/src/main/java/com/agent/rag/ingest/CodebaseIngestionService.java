package com.agent.rag.ingest;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.store.RagStore;
import com.agent.sandbox.ast.AstService;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 读取、切片、向量化并事务替换一个代码库索引。 */
public final class CodebaseIngestionService {

    private final CodebaseChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final RagStore ragStore;

    /** 创建 ingest 服务。 */
    public CodebaseIngestionService(
            AstService astService,
            EmbeddingModel embeddingModel,
            RagStore ragStore) {
        this.chunker = new CodebaseChunker(Objects.requireNonNull(astService, "astService 不能为空"));
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel 不能为空");
        this.ragStore = Objects.requireNonNull(ragStore, "ragStore 不能为空");
        if (embeddingModel.dimensions() != ChildChunk.EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("EmbeddingModel dimensions 必须为 8");
        }
    }

    /** 完成一次 repositoryId 的全量替换 ingest。 */
    public void ingest(Path repositoryRoot, String repositoryId) {
        ChunkBatch batch = chunker.chunk(repositoryRoot, repositoryId);
        List<ChildChunk> children = batch.children().stream()
                .map(this::embed)
                .toList();
        ragStore.replaceRepository(repositoryId, batch.parents(), children);
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
