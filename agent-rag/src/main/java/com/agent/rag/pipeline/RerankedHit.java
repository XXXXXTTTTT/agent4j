package com.agent.rag.pipeline;

import java.util.Objects;
import java.util.UUID;

/** rerank 阶段返回的命中标识和精排分数。 */
public record RerankedHit(UUID childId, double score) {

    /** 校验命中标识和分数。 */
    public RerankedHit {
        Objects.requireNonNull(childId, "childId 不能为空");
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score 必须是有限非负数");
        }
    }
}
