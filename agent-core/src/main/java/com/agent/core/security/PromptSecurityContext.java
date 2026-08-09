package com.agent.core.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Prompt 文本的安全检查上下文。 */
public record PromptSecurityContext(
        UUID runId,
        String userId,
        String nodeName,
        String source) {

    private static final Set<String> SOURCES = Set.of(
            "user.task", "project.knowledge", "tool.output");

    public PromptSecurityContext {
        Objects.requireNonNull(runId, "runId 不能为空");
        requireText(userId, "userId");
        requireText(nodeName, "nodeName");
        requireText(source, "source");
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("source 不在允许集合中: " + source);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
