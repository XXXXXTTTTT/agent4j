package com.agent.core.command;

import java.time.Instant;
import java.util.Objects;

/** Slash Command 生命周期审计事件。 */
public record CommandAuditEvent(
        Instant occurredAt,
        String actorId,
        String workspaceId,
        String conversationId,
        String commandName,
        CommandResult.Status status,
        String message) {

    /** 校验审计字段。 */
    public CommandAuditEvent {
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        requireText(actorId, "actorId");
        requireText(workspaceId, "workspaceId");
        requireText(conversationId, "conversationId");
        requireText(commandName, "commandName");
        Objects.requireNonNull(status, "status 不能为空");
        message = Objects.requireNonNullElse(message, "");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
