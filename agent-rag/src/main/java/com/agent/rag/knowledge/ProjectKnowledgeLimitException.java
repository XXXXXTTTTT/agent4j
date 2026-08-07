package com.agent.rag.knowledge;

import java.util.Locale;
import java.util.Objects;

/** 项目知识文件超过字节、行数或 token 限制。 */
public final class ProjectKnowledgeLimitException extends ProjectKnowledgeException {

    private final String relativePath;
    private final ProjectKnowledgeLimitKind kind;
    private final int observed;
    private final int limit;

    /** 创建包含精确观测值和限制值的异常。 */
    public ProjectKnowledgeLimitException(
            String relativePath,
            ProjectKnowledgeLimitKind kind,
            int observed,
            int limit) {
        super(formatMessage(relativePath, kind, observed, limit));
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }
        this.relativePath = relativePath;
        this.kind = Objects.requireNonNull(kind, "kind 不能为空");
        if (observed < 0 || limit < 0) {
            throw new IllegalArgumentException("observed 和 limit 不能为负数");
        }
        this.observed = observed;
        this.limit = limit;
    }

    public String relativePath() {
        return relativePath;
    }

    public ProjectKnowledgeLimitKind kind() {
        return kind;
    }

    public int observed() {
        return observed;
    }

    public int limit() {
        return limit;
    }

    private static String formatMessage(
            String relativePath,
            ProjectKnowledgeLimitKind kind,
            int observed,
            int limit) {
        return String.format(
                Locale.ROOT,
                "知识文件 %s 超出 %s 限制: observed=%,d, limit=%,d",
                relativePath,
                kind,
                observed,
                limit);
    }
}
