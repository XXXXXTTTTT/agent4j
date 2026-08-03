package com.agent.core.memory;

import java.util.Objects;

/** 规划节点的长期记忆召回请求。 */
public record MemoryContextRequest(
        String repositoryId,
        String userId,
        String query,
        int limit) {

    /** 校验记忆范围、查询文本与召回数量。 */
    public MemoryContextRequest {
        requireText(repositoryId, "repositoryId");
        requireText(userId, "userId");
        requireText(query, "query");
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit 必须在 1 到 20 之间");
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
