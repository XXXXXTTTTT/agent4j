package com.agent.rag.store;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 一个仓库当前已提交 RAG 索引的内容指纹与块数量。 */
public record RagRepositoryIndex(
        String repositoryId,
        String workspaceFingerprint,
        int parentCount,
        int childCount,
        Instant indexedAt) {

    /** 校验仓库标识、内容哈希、数量与索引时间。 */
    public RagRepositoryIndex {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId 不能为空");
        }
        if (workspaceFingerprint == null
                || !workspaceFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "workspaceFingerprint 必须是 64 位小写 SHA-256");
        }
        if (parentCount < 0) {
            throw new IllegalArgumentException("parentCount 不能为负数");
        }
        if (childCount < 0) {
            throw new IllegalArgumentException("childCount 不能为负数");
        }
        indexedAt = Objects.requireNonNull(indexedAt, "indexedAt 不能为空")
                .truncatedTo(ChronoUnit.MICROS);
    }
}
