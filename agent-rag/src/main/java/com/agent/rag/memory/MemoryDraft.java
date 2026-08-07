package com.agent.rag.memory;

import java.util.Objects;

/** 模型提取出的单条长期记忆草稿。 */
public record MemoryDraft(
        MemoryType type,
        String title,
        String content,
        double importance) {

    /** 使用中等重要度创建兼容的记忆草稿。 */
    public MemoryDraft(MemoryType type, String title, String content) {
        this(type, title, content, 0.5);
    }

    /** 校验类型、标题和正文长度。 */
    public MemoryDraft {
        Objects.requireNonNull(type, "type 不能为空");
        requireText(title, "title");
        requireText(content, "content");
        if (title.length() > 200) {
            throw new IllegalArgumentException("title 不能超过 200 个字符");
        }
        if (content.length() > 4_000) {
            throw new IllegalArgumentException("content 不能超过 4000 个字符");
        }
        requireImportance(importance);
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }

    private static void requireImportance(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("importance 必须是 0.0 到 1.0 的有限数");
        }
    }
}
