package com.agent.rag.memory;

import java.util.Objects;

/** 长期记忆的混合召回结果。 */
public record MemoryHit(
        MemoryEntry entry,
        double vectorScore,
        double lexicalScore,
        double finalScore) {

    /** 校验命中条目和所有分数。 */
    public MemoryHit {
        Objects.requireNonNull(entry, "entry 不能为空");
        validateScore(vectorScore, "vectorScore");
        validateScore(lexicalScore, "lexicalScore");
        validateScore(finalScore, "finalScore");
    }

    private static void validateScore(double score, String name) {
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException(name + " 必须是有限非负数");
        }
    }
}
