package com.agent.rag.domain;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** 父块内可独立检索的子块。 */
public record ChildChunk(
        UUID childId,
        UUID parentId,
        String repositoryId,
        String path,
        String symbol,
        int ordinal,
        String content,
        int startLine,
        int endLine,
        float[] embedding) {

    /** 当前数据库 schema 固定的向量维度。 */
    public static final int EMBEDDING_DIMENSIONS = 8;

    /** 创建并校验子块。 */
    public ChildChunk {
        Objects.requireNonNull(childId, "childId 不能为空");
        Objects.requireNonNull(parentId, "parentId 不能为空");
        repositoryId = requireText(repositoryId, "repositoryId 不能为空");
        path = requireRelativePath(path);
        symbol = optionalText(symbol, "symbol 不能为空白字符串");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal 不能小于 0");
        }
        content = Objects.requireNonNull(content, "content 不能为空");
        if (startLine <= 0 || endLine < startLine) {
            throw new IllegalArgumentException("源码行号范围无效");
        }
        Objects.requireNonNull(embedding, "embedding 不能为空");
        if (embedding.length != EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("embedding 维度必须为 8");
        }
        embedding = Arrays.copyOf(embedding, embedding.length);
    }

    @Override
    public float[] embedding() {
        return Arrays.copyOf(embedding, embedding.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChildChunk that)) {
            return false;
        }
        return ordinal == that.ordinal
                && startLine == that.startLine
                && endLine == that.endLine
                && Objects.equals(childId, that.childId)
                && Objects.equals(parentId, that.parentId)
                && Objects.equals(repositoryId, that.repositoryId)
                && Objects.equals(path, that.path)
                && Objects.equals(symbol, that.symbol)
                && Objects.equals(content, that.content)
                && Arrays.equals(embedding, that.embedding);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                childId, parentId, repositoryId, path, symbol,
                ordinal, content, startLine, endLine);
        return 31 * result + Arrays.hashCode(embedding);
    }

    private static String requireRelativePath(String value) {
        String path = requireText(value, "path 不能为空");
        if (Path.of(path).isAbsolute() || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("path 必须是使用 / 分隔的相对路径");
        }
        return path;
    }

    private static String optionalText(String value, String message) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
