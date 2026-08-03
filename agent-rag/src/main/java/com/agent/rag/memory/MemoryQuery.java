package com.agent.rag.memory;

import java.util.Objects;
import java.util.Set;

/** 长期记忆召回请求。 */
public record MemoryQuery(
        String repositoryId,
        String userId,
        String query,
        Set<MemoryType> types,
        int limit) {

    /** 校验范围、类型过滤和召回数量。 */
    public MemoryQuery {
        requireText(repositoryId, "repositoryId");
        requireText(userId, "userId");
        requireText(query, "query");
        Objects.requireNonNull(types, "types 不能为空");
        if (types.isEmpty() || types.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("types 必须是非空类型集合");
        }
        types = Set.copyOf(types);
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
