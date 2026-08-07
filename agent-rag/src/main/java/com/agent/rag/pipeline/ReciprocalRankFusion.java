package com.agent.rag.pipeline;

import com.agent.rag.domain.RagHit;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 使用固定 RRF 排名常量融合多组基础召回结果。 */
public final class ReciprocalRankFusion {

    private static final int RANK_CONSTANT = 60;

    /** 按 childId 合并重复命中并返回确定性排序结果。 */
    public List<FusedHit> fuse(List<List<RagHit>> rankedLists) {
        Objects.requireNonNull(rankedLists, "rankedLists 不能为空");
        Map<UUID, RagHit> hitsById = new LinkedHashMap<>();
        Map<UUID, Double> scoresById = new HashMap<>();
        for (List<RagHit> rankedList : rankedLists) {
            Objects.requireNonNull(rankedList, "rankedList 不能为空");
            for (int index = 0; index < rankedList.size(); index++) {
                RagHit hit = Objects.requireNonNull(
                        rankedList.get(index), "rankedList 不能包含 null");
                UUID childId = hit.childChunk().childId();
                RagHit existing = hitsById.putIfAbsent(childId, hit);
                if (existing != null) {
                    ensureSameContent(existing, hit);
                }
                int rank = index + 1;
                scoresById.merge(
                        childId, 1.0 / (RANK_CONSTANT + rank), Double::sum);
            }
        }
        return hitsById.entrySet().stream()
                .map(entry -> new FusedHit(
                        entry.getValue(), scoresById.get(entry.getKey())))
                .sorted(Comparator.comparingDouble(FusedHit::score).reversed()
                        .thenComparing(item -> item.hit().childChunk().path())
                        .thenComparingInt(item -> item.hit().childChunk().ordinal())
                        .thenComparing(item -> item.hit().childChunk().childId()))
                .toList();
    }

    private void ensureSameContent(RagHit existing, RagHit incoming) {
        if (!existing.childChunk().equals(incoming.childChunk())
                || !existing.parentChunk().equals(incoming.parentChunk())) {
            throw new IllegalArgumentException("同一 childId 的融合命中内容不一致");
        }
    }
}
