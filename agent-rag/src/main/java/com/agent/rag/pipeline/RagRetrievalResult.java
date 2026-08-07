package com.agent.rag.pipeline;

import java.util.List;
import java.util.Objects;

/** RAG 流水线最终文档和完整阶段证据。 */
public record RagRetrievalResult(
        List<RagContextDocument> documents,
        List<RagStageEvidence> evidence,
        int estimatedTokens,
        boolean degraded) {

    /** 冻结集合并校验 token 与降级标记。 */
    public RagRetrievalResult {
        documents = List.copyOf(Objects.requireNonNull(documents, "documents 不能为空"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence 不能为空"));
        if (documents.stream().anyMatch(Objects::isNull)
                || evidence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("结果集合不能包含 null");
        }
        long documentTokens = documents.stream()
                .mapToLong(RagContextDocument::estimatedTokens)
                .sum();
        if (documentTokens > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("文档 token 总数超过整数上限");
        }
        if (estimatedTokens != documentTokens) {
            throw new IllegalArgumentException("estimatedTokens 必须等于文档 token 总数");
        }
        boolean hasDegradedStage = evidence.stream()
                .anyMatch(item -> item.status() == RagStageStatus.DEGRADED);
        if (degraded != hasDegradedStage) {
            throw new IllegalArgumentException("degraded 与阶段证据不一致");
        }
    }
}
