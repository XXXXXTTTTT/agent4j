package com.agent.rag.domain;

import java.util.Arrays;

/** 一次代码库检索请求。 */
public record RagQuery(
        String repositoryId,
        String query,
        float[] queryEmbedding,
        int limit) {

    /** 创建并校验检索请求。 */
    public RagQuery {
        repositoryId = requireText(repositoryId, "repositoryId 不能为空");
        query = requireText(query, "query 不能为空");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        if (queryEmbedding != null) {
            if (queryEmbedding.length != ChildChunk.EMBEDDING_DIMENSIONS) {
                throw new IllegalArgumentException("queryEmbedding 维度必须为 8");
            }
            queryEmbedding = Arrays.copyOf(queryEmbedding, queryEmbedding.length);
        }
    }

    @Override
    public float[] queryEmbedding() {
        return queryEmbedding == null
                ? null
                : Arrays.copyOf(queryEmbedding, queryEmbedding.length);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
