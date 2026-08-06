package com.agent.web.conversation;

import com.agent.web.identity.Actor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话及轮次的 PostgreSQL 权威存储端口。 */
public interface ConversationRepository {

    default List<ConversationRecord> findConversations(UUID workspaceId, String userId, String query) {
        throw unsupported("findConversations");
    }

    default Optional<ConversationRecord> findConversation(UUID conversationId, String userId) {
        throw unsupported("findConversation");
    }

    default ConversationRecord createConversation(
            UUID conversationId,
            UUID workspaceId,
            Actor actor,
            String title,
            Instant now) {
        throw unsupported("createConversation");
    }

    default ConversationRecord archiveConversation(UUID conversationId, String userId, Instant now) {
        throw unsupported("archiveConversation");
    }

    default ConversationTurnRecord createPendingTurn(
            UUID conversationId,
            String userId,
            String userContent,
            Instant now) {
        throw unsupported("createPendingTurn");
    }

    default Optional<ConversationTurnRecord> findTurn(UUID turnId, String userId) {
        throw unsupported("findTurn");
    }

    default Optional<ConversationTurnRecord> findTurnByRunId(UUID runId, String userId) {
        throw unsupported("findTurnByRunId");
    }

    List<ConversationTurnRecord> findTurns(UUID conversationId, String userId);

    default ConversationTurnRecord markTurnRunning(UUID turnId, UUID runId, Instant now) {
        throw unsupported("markTurnRunning");
    }

    default ConversationTurnRecord markTurnCompleted(
            UUID turnId,
            String assistantContent,
            Instant now) {
        throw unsupported("markTurnCompleted");
    }

    default ConversationTurnRecord markTurnFailed(UUID turnId, String error, Instant now) {
        throw unsupported("markTurnFailed");
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(method + " 尚未由该仓储实现");
    }
}
