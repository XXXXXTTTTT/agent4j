package com.agent.rag.store;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;

import java.util.List;

/** RAG 索引存储端口。 */
public interface RagStore {

    /** 在同一事务中替换一个 repositoryId 的全部父子块。 */
    void replaceRepository(
            String repositoryId,
            List<ParentChunk> parents,
            List<ChildChunk> children);
}
