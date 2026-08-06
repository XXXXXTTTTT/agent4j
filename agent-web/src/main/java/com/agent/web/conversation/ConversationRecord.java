package com.agent.web.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 当前调用者可见的会话元数据。 */
public record ConversationRecord(
        UUID conversationId,
        UUID workspaceId,
        String createdBy,
        String title,
        ConversationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /** 校验会话记录并冻结生命周期字段。 */
    public ConversationRecord {
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        requireText(createdBy, "createdBy");
        requireText(title, "title");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
