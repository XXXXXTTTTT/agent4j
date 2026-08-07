package com.agent.rag.pipeline;

import com.agent.core.context.TokenEstimator;
import com.agent.core.llm.ChatMessage;
import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 按完整父子块原子单位执行 RAG 上下文 token 门禁。 */
public final class RagTokenBudgetSelector {

    private final TokenEstimator tokenEstimator;

    /** 注入与模型上下文一致的 token 估算器。 */
    public RagTokenBudgetSelector(TokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(
                tokenEstimator, "tokenEstimator 不能为空");
    }

    /** 按精排顺序选择不超过 maxTokens 的完整上下文文档。 */
    public List<RagContextDocument> select(
            List<FusedHit> fusedHits,
            List<RerankedHit> rerankedHits,
            int maxTokens) {
        Objects.requireNonNull(fusedHits, "fusedHits 不能为空");
        Objects.requireNonNull(rerankedHits, "rerankedHits 不能为空");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens 必须大于 0");
        }
        if (rerankedHits.isEmpty()) {
            return List.of();
        }
        List<RerankedHit> validated = RerankValidation.validate(
                fusedHits, rerankedHits, rerankedHits.size());
        Map<UUID, FusedHit> fusedByChildId = indexFusedHits(fusedHits);
        Set<UUID> selectedParentIds = new HashSet<>();
        List<RagContextDocument> selected = new ArrayList<>();
        int remainingTokens = maxTokens;

        for (RerankedHit rerankedHit : validated) {
            FusedHit fusedHit = fusedByChildId.get(rerankedHit.childId());
            ParentChunk parent = fusedHit.hit().parentChunk();
            if (selectedParentIds.contains(parent.parentId())) {
                continue;
            }
            int parentTokens = estimate(parent.content());
            if (parentTokens <= remainingTokens) {
                selected.add(parentDocument(
                        fusedHit, rerankedHit, parent, parentTokens));
                selectedParentIds.add(parent.parentId());
                remainingTokens -= parentTokens;
                continue;
            }

            ChildChunk child = fusedHit.hit().childChunk();
            int childTokens = estimate(child.content());
            if (childTokens <= remainingTokens) {
                selected.add(childDocument(
                        fusedHit, rerankedHit, child, childTokens));
                selectedParentIds.add(parent.parentId());
                remainingTokens -= childTokens;
            } else if (selected.isEmpty() && childTokens > maxTokens) {
                throw new RagContextBudgetExceededException(
                        childTokens, maxTokens);
            }
        }
        return List.copyOf(selected);
    }

    private Map<UUID, FusedHit> indexFusedHits(List<FusedHit> fusedHits) {
        Map<UUID, FusedHit> indexed = new HashMap<>();
        for (FusedHit hit : fusedHits) {
            if (hit == null) {
                throw new IllegalArgumentException("fusedHits 不能包含 null");
            }
            UUID childId = hit.hit().childChunk().childId();
            if (indexed.putIfAbsent(childId, hit) != null) {
                throw new IllegalArgumentException(
                        "fusedHits 包含重复 childId: " + childId);
            }
        }
        return indexed;
    }

    private int estimate(String content) {
        int estimatedTokens = tokenEstimator.estimate(ChatMessage.user(content));
        if (estimatedTokens < 1) {
            throw new IllegalStateException("TokenEstimator 必须返回正整数");
        }
        return estimatedTokens;
    }

    private RagContextDocument parentDocument(
            FusedHit fusedHit,
            RerankedHit rerankedHit,
            ParentChunk parent,
            int estimatedTokens) {
        return new RagContextDocument(
                fusedHit.hit().childChunk().childId(),
                parent.parentId(),
                parent.path(),
                parent.symbol(),
                parent.startLine(),
                parent.endLine(),
                parent.content(),
                RagContentSource.PARENT,
                fusedHit.score(),
                rerankedHit.score(),
                estimatedTokens);
    }

    private RagContextDocument childDocument(
            FusedHit fusedHit,
            RerankedHit rerankedHit,
            ChildChunk child,
            int estimatedTokens) {
        return new RagContextDocument(
                child.childId(),
                child.parentId(),
                child.path(),
                child.symbol(),
                child.startLine(),
                child.endLine(),
                child.content(),
                RagContentSource.CHILD,
                fusedHit.score(),
                rerankedHit.score(),
                estimatedTokens);
    }
}
