package com.agent.rag.pipeline;

import com.agent.core.intent.TaskComplexity;

import java.util.Objects;

/** 一次自适应 RAG 检索请求。 */
public record RagRetrievalRequest(
        String repositoryId,
        String query,
        TaskComplexity complexity,
        RagRetrievalPolicy policy) {

    /** 校验检索范围、文本与策略。 */
    public RagRetrievalRequest {
        repositoryId = requireText(repositoryId, "repositoryId 不能为空");
        query = requireText(query, "query 不能为空");
        Objects.requireNonNull(complexity, "complexity 不能为空");
        Objects.requireNonNull(policy, "policy 不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
