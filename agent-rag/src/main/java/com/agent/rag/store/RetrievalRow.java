package com.agent.rag.store;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;

import java.util.Objects;

/** 数据库返回的一条父子召回行。 */
public record RetrievalRow(
        ChildChunk childChunk,
        ParentChunk parentChunk,
        double retrievalScore) {

    /** 创建并校验召回行。 */
    public RetrievalRow {
        Objects.requireNonNull(childChunk, "childChunk 不能为空");
        Objects.requireNonNull(parentChunk, "parentChunk 不能为空");
        if (!childChunk.parentId().equals(parentChunk.parentId())) {
            throw new IllegalArgumentException("父子块 parentId 不一致");
        }
        if (!Double.isFinite(retrievalScore) || retrievalScore < 0) {
            throw new IllegalArgumentException("retrievalScore 必须是有限非负数");
        }
    }
}
