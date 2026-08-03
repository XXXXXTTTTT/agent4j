package com.agent.rag.memory;

import java.util.List;

/** 长期记忆持久化和两路召回端口。 */
public interface MemoryStore {

    /** 在一个事务中批量 upsert 并返回数据库最终条目。 */
    List<MemoryEntry> upsertAll(List<MemoryEntry> entries);

    /** 按精确 scope/type 执行向量召回。 */
    List<MemoryRetrievalRow> findByVector(MemoryQuery query, float[] queryEmbedding, int limit);

    /** 按精确 scope/type 执行词法召回。 */
    List<MemoryRetrievalRow> findByLexical(MemoryQuery query, int limit);
}
