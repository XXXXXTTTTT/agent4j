package com.agent.web.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 可写入 JSON Lines 的不可变会话审计事件。 */
public record ConversationAuditEvent(
        ConversationAuditEventType eventType,
        Instant occurredAt,
        String userId,
        UUID workspaceId,
        UUID conversationId,
        UUID turnId,
        UUID runId,
        Long turnIndex,
        String status,
        String userContent,
        String assistantContent,
        String error,
        Long durationMs) {

    public ConversationAuditEvent {
        Objects.requireNonNull(eventType, "eventType 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        if (turnIndex != null && turnIndex < 1) {
            throw new IllegalArgumentException("turnIndex 必须从 1 开始");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs 不能为负数");
        }
    }
}
