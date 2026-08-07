package com.agent.rag.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 校验外部精排实现是否遵守 childId 与数量边界。 */
public final class RerankValidation {

    private RerankValidation() {
    }

    /** 校验并冻结外部精排返回值。 */
    public static List<RerankedHit> validate(
            List<FusedHit> sourceHits,
            List<RerankedHit> rerankedHits,
            int limit) {
        Objects.requireNonNull(sourceHits, "sourceHits 不能为空");
        Objects.requireNonNull(rerankedHits, "rerankedHits 不能为空");
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        if (rerankedHits.size() > limit) {
            throw new IllegalArgumentException(
                    "rerank 返回数量超过 limit: "
                            + rerankedHits.size() + " > " + limit);
        }
        if (sourceHits.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("sourceHits 不能包含 null");
        }
        Set<UUID> sourceIds = new HashSet<>();
        for (FusedHit hit : sourceHits) {
            sourceIds.add(hit.hit().childChunk().childId());
        }
        Set<UUID> returnedIds = new HashSet<>();
        for (RerankedHit hit : rerankedHits) {
            if (hit == null) {
                throw new IllegalArgumentException("rerank 结果不能包含 null");
            }
            if (!sourceIds.contains(hit.childId())) {
                throw new IllegalArgumentException(
                        "rerank 返回未知 childId: " + hit.childId());
            }
            if (!returnedIds.add(hit.childId())) {
                throw new IllegalArgumentException(
                        "rerank 返回重复 childId: " + hit.childId());
            }
        }
        return List.copyOf(rerankedHits);
    }
}
