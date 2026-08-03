package com.agent.rag.memory;

import java.util.Objects;

/** JDBC 两路召回返回的单行。 */
public record MemoryRetrievalRow(MemoryEntry entry, double retrievalScore) {

    /** 校验条目和数据库分数。 */
    public MemoryRetrievalRow {
        Objects.requireNonNull(entry, "entry 不能为空");
        if (!Double.isFinite(retrievalScore) || retrievalScore < 0) {
            throw new IllegalArgumentException("retrievalScore 必须是有限非负数");
        }
    }
}
