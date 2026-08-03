package com.agent.rag.store;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;

import java.util.List;
import java.util.Map;

/** RAG 索引存储端口。 */
public interface RagStore {

    /** 在同一事务中替换一个 repositoryId 的全部父子块。 */
    void replaceRepository(
            String repositoryId,
            List<ParentChunk> parents,
            List<ChildChunk> children);

    /** 按余弦距离返回向量召回行。 */
    List<RetrievalRow> findByVector(String repositoryId, float[] queryEmbedding, int limit);

    /** 按 PostgreSQL GIN 词法索引返回召回行。 */
    List<RetrievalRow> findByLexical(String repositoryId, String query, int limit);

    /** 返回 repositoryId 下的子块数量。 */
    long countChildren(String repositoryId);

    /** 返回 repositoryId 下的平均 token 长度。 */
    double averageDocumentLength(String repositoryId);

    /** 返回查询词在 repositoryId 下的文档频率。 */
    Map<String, Long> documentFrequencies(String repositoryId, List<String> terms);
}
