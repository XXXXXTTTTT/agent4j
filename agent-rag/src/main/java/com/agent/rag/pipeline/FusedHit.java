package com.agent.rag.pipeline;

import com.agent.rag.domain.RagHit;

import java.util.Objects;

/** 多查询融合后的原始命中与 RRF 分数。 */
public record FusedHit(RagHit hit, double score) {

    /** 校验命中和融合分数。 */
    public FusedHit {
        Objects.requireNonNull(hit, "hit 不能为空");
        requireScore(score);
    }

    private static void requireScore(double score) {
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score 必须是有限非负数");
        }
    }
}
