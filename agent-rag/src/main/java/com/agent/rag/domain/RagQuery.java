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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RagQuery that)) {
            return false;
        }
        return limit == that.limit
                && repositoryId.equals(that.repositoryId)
                && query.equals(that.query)
                && Arrays.equals(queryEmbedding, that.queryEmbedding);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(repositoryId, query, limit);
        return 31 * result + Arrays.hashCode(queryEmbedding);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
