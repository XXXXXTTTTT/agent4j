package com.agent.web.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 会话中的一轮用户输入及其 Run 终态结果。 */
public record ConversationTurnRecord(
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

    /** 校验轮次状态与终态数据的对应关系。 */
    public ConversationTurnRecord {
        Objects.requireNonNull(turnId, "turnId 不能为空");
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        if (turnIndex < 1) {
            throw new IllegalArgumentException("turnIndex 必须从 1 开始");
        }
        requireText(userContent, "userContent");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (status == ConversationTurnStatus.COMPLETED) {
            requireText(assistantContent, "assistantContent");
            if (error != null) {
                throw new IllegalArgumentException("COMPLETED 轮次不能包含 error");
            }
            Objects.requireNonNull(completedAt, "COMPLETED 轮次必须包含 completedAt");
        }
        if (status == ConversationTurnStatus.FAILED) {
            requireText(error, "error");
            if (assistantContent != null) {
                throw new IllegalArgumentException("FAILED 轮次不能包含 assistantContent");
            }
            Objects.requireNonNull(completedAt, "FAILED 轮次必须包含 completedAt");
        }
        if (status == ConversationTurnStatus.PENDING || status == ConversationTurnStatus.RUNNING) {
            if (error != null) {
                throw new IllegalArgumentException(status + " 轮次不能包含 error");
            }
            if (completedAt != null) {
                throw new IllegalArgumentException(status + " 轮次不能包含 completedAt");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
