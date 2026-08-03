package com.agent.rag.memory;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** 已持久化的长期记忆条目。 */
public record MemoryEntry(
        UUID memoryId,
        String repositoryId,
        String userId,
        MemoryType type,
        String title,
        String content,
        String contentHash,
        float[] embedding,
        Instant createdAt,
        Instant updatedAt) {

    /** 校验字段并防御性复制 embedding。 */
    public MemoryEntry {
        Objects.requireNonNull(memoryId, "memoryId 不能为空");
        requireText(repositoryId, "repositoryId");
        requireText(userId, "userId");
        Objects.requireNonNull(type, "type 不能为空");
        requireText(title, "title");
        requireText(content, "content");
        if (title.length() > 200) {
            throw new IllegalArgumentException("title 不能超过 200 个字符");
        }
        if (content.length() > 4_000) {
            throw new IllegalArgumentException("content 不能超过 4000 个字符");
        }
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash 必须是 64 位小写 SHA-256 十六进制文本");
        }
        embedding = copyEmbedding(embedding);
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
        }
    }

    @Override
    public float[] embedding() {
        return Arrays.copyOf(embedding, embedding.length);
    }

    /** 以 embedding 元素值参与相等判断，而不是比较数组引用。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MemoryEntry that)) {
            return false;
        }
        return Objects.equals(memoryId, that.memoryId)
                && Objects.equals(repositoryId, that.repositoryId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(type, that.type)
                && Objects.equals(title, that.title)
                && Objects.equals(content, that.content)
                && Objects.equals(contentHash, that.contentHash)
                && Arrays.equals(embedding, that.embedding)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt);
    }

    /** 与 equals 保持 embedding 数组的值语义一致。 */
    @Override
    public int hashCode() {
        int result = Objects.hash(
                memoryId, repositoryId, userId, type, title, content,
                contentHash, createdAt, updatedAt);
        return 31 * result + Arrays.hashCode(embedding);
    }

    private static float[] copyEmbedding(float[] value) {
        Objects.requireNonNull(value, "embedding 不能为空");
        if (value.length != 8) {
            throw new IllegalArgumentException("embedding 必须为 8 维");
        }
        for (float element : value) {
            if (!Float.isFinite(element)) {
                throw new IllegalArgumentException("embedding 必须只包含有限数");
            }
        }
        return Arrays.copyOf(value, value.length);
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
