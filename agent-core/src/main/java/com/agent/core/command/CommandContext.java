package com.agent.core.command;

import java.util.Map;
import java.util.Objects;

/** 一次命令调用的身份和资源边界。 */
public record CommandContext(
        String actorId,
        String workspaceId,
        String conversationId,
        Map<String, String> variables) {

    /** 保留不带扩展变量的构造器。 */
    public CommandContext(String actorId, String workspaceId, String conversationId) {
        this(actorId, workspaceId, conversationId, Map.of());
    }

    /** 校验身份和资源标识。 */
    public CommandContext {
        requireText(actorId, "actorId");
        requireText(workspaceId, "workspaceId");
        requireText(conversationId, "conversationId");
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        if (variables.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException("variables 的键和值不能为 null");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
