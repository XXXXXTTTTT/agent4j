package com.agent.rag.domain;

import java.util.Objects;

/** 混合检索返回的完整父子块及评分。 */
public record RagHit(
        ChildChunk childChunk,
        ParentChunk parentChunk,
        double vectorScore,
        double bm25Score,
        double symbolScore,
        double finalScore) {

    /** 创建并校验命中结果。 */
    public RagHit {
        Objects.requireNonNull(childChunk, "childChunk 不能为空");
        Objects.requireNonNull(parentChunk, "parentChunk 不能为空");
        if (!childChunk.parentId().equals(parentChunk.parentId())) {
            throw new IllegalArgumentException("父子块 parentId 不一致");
        }
        requireScore(vectorScore, "vectorScore");
        requireScore(bm25Score, "bm25Score");
        requireScore(symbolScore, "symbolScore");
        requireScore(finalScore, "finalScore");
    }

    private static void requireScore(double score, String name) {
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException(name + " 必须是有限非负数");
        }
    }
}
