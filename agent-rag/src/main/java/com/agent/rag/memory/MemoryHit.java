package com.agent.rag.memory;

import java.util.Objects;

/** 长期记忆的混合召回结果。 */
public record MemoryHit(
        MemoryEntry entry,
        double vectorScore,
        double lexicalScore,
        double finalScore,
        double lifecycleScore,
        double rankingScore) {

    /** 使用兼容生命周期分数创建命中。 */
    public MemoryHit(
            MemoryEntry entry,
            double vectorScore,
            double lexicalScore,
            double finalScore) {
        this(
                entry,
                vectorScore,
                lexicalScore,
                finalScore,
                1.0,
                0.8 * finalScore + 0.2);
    }

    /** 校验命中条目和所有分数。 */
    public MemoryHit {
        Objects.requireNonNull(entry, "entry 不能为空");
        validateScore(vectorScore, "vectorScore");
        validateScore(lexicalScore, "lexicalScore");
        validateScore(finalScore, "finalScore");
        validateScore(lifecycleScore, "lifecycleScore");
        validateScore(rankingScore, "rankingScore");
    }

    private static void validateScore(double score, String name) {
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException(name + " 必须是有限非负数");
        }
    }
}
