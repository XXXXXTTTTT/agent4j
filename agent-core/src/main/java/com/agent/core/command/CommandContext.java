package com.agent.core.command;

/** 一次命令调用的身份和资源边界。 */
public record CommandContext(String actorId, String workspaceId, String conversationId) {

    /** 校验身份和资源标识。 */
    public CommandContext {
        requireText(actorId, "actorId");
        requireText(workspaceId, "workspaceId");
        requireText(conversationId, "conversationId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
