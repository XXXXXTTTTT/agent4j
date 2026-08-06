package com.agent.web.controller;

import com.agent.web.conversation.ConversationRecord;
import com.agent.web.conversation.ConversationStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 会话元数据 HTTP 视图。 */
public record ConversationView(
        UUID conversationId,
        UUID workspaceId,
        String createdBy,
        String title,
        ConversationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static ConversationView from(ConversationRecord conversation) {
        Objects.requireNonNull(conversation, "conversation 不能为空");
        return new ConversationView(
                conversation.conversationId(), conversation.workspaceId(), conversation.createdBy(),
                conversation.title(), conversation.status(), conversation.createdAt(), conversation.updatedAt());
    }
}
