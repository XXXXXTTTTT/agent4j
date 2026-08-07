package com.agent.rag.pipeline;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** 经过排序和预算选择后实际注入的代码证据。 */
public record RagContextDocument(
        UUID childId,
        UUID parentId,
        String path,
        String symbol,
        int startLine,
        int endLine,
        String content,
        RagContentSource contentSource,
        double retrievalScore,
        double rerankScore,
        int estimatedTokens) {

    /** 校验标识、相对路径、行号、分数和 token。 */
    public RagContextDocument {
        Objects.requireNonNull(childId, "childId 不能为空");
        Objects.requireNonNull(parentId, "parentId 不能为空");
        path = requireText(path, "path 不能为空");
        if (Path.of(path).isAbsolute() || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("path 必须是使用 / 分隔的相对路径");
        }
        if (symbol != null && symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 不能为空白字符串");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("行号范围无效");
        }
        content = requireText(content, "content 不能为空");
        Objects.requireNonNull(contentSource, "contentSource 不能为空");
        requireScore(retrievalScore, "retrievalScore");
        requireScore(rerankScore, "rerankScore");
        if (estimatedTokens < 1) {
            throw new IllegalArgumentException("estimatedTokens 必须大于 0");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static void requireScore(double score, String name) {
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException(name + " 必须是有限非负数");
        }
    }
}
