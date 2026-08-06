package com.agent.web.controller;

import com.agent.web.conversation.ConversationTurnRecord;
import com.agent.web.conversation.ConversationTurnStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 会话轮次 HTTP 视图。 */
public record ConversationTurnView(
        UUID turnId,
        UUID conversationId,
        long turnIndex,
        String userContent,
        String assistantContent,
        UUID runId,
        ConversationTurnStatus status,
        String error,
        Instant createdAt,
        Instant completedAt) {

    public static ConversationTurnView from(ConversationTurnRecord turn) {
        Objects.requireNonNull(turn, "turn 不能为空");
        return new ConversationTurnView(
                turn.turnId(), turn.conversationId(), turn.turnIndex(), turn.userContent(),
                turn.assistantContent(), turn.runId(), turn.status(), turn.error(),
                turn.createdAt(), turn.completedAt());
    }
}
