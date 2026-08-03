package com.agent.rag.domain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** 代码库中的稳定父级语义块。 */
public record ParentChunk(
        UUID parentId,
        String repositoryId,
        String path,
        String symbol,
        String content,
        int startLine,
        int endLine,
        String metadataJson) {

    /** 创建并校验父块。 */
    public ParentChunk {
        Objects.requireNonNull(parentId, "parentId 不能为空");
        repositoryId = requireText(repositoryId, "repositoryId 不能为空");
        path = requireRelativePath(path);
        symbol = optionalText(symbol, "symbol 不能为空白字符串");
        content = Objects.requireNonNull(content, "content 不能为空");
        validateRange(startLine, endLine);
        metadataJson = requireText(metadataJson, "metadataJson 不能为空");
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

    private static void validateRange(int startLine, int endLine) {
        if (startLine <= 0 || endLine < startLine) {
            throw new IllegalArgumentException("源码行号范围无效");
        }
    }
}
