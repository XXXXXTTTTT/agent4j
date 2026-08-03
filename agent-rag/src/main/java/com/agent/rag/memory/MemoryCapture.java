package com.agent.rag.memory;

import java.util.Objects;

/** 待模型提取的原始长期记忆观察。 */
public record MemoryCapture(String repositoryId, String userId, String sourceText) {

    /** 校验记忆范围和原始文本大小。 */
    public MemoryCapture {
        requireText(repositoryId, "repositoryId");
        requireText(userId, "userId");
        requireText(sourceText, "sourceText");
        if (sourceText.length() > 20_000) {
            throw new IllegalArgumentException("sourceText 不能超过 20000 个字符");
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
