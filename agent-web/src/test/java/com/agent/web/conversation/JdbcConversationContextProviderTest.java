package com.agent.web.conversation;

import com.agent.core.conversation.ConversationContext;
import com.agent.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConversationContextProviderTest {

    private static final UUID CONVERSATION_ID =
            UUID.fromString("7c45f8d1-f547-4bdc-8dd0-2c654be26c1e");
    private static final UUID TURN_ID =
            UUID.fromString("d7c78825-4260-4ae9-b10f-7349dbe27d8f");
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    @Test
    void retainsRecentCompletePairsWithinBothBudgets() {
        ConversationRepository repository = new ConversationRepository() {
            @Override
            public List<ConversationTurnRecord> findTurns(UUID conversationId, String userId) {
                return List.of(
                        completed(1, "第一轮用户", "第一轮回答"),
                        completed(2, "第二轮用户", "第二轮回答"),
                        new ConversationTurnRecord(
                                TURN_ID,
                                conversationId,
                                3,
                                "正在执行",
                                null,
                                null,
                                ConversationTurnStatus.RUNNING,
                                null,
                                NOW,
                                null));
            }
        };
        JdbcConversationContextProvider provider = new JdbcConversationContextProvider(repository);

        ConversationContext context = provider.load(CONVERSATION_ID, "user", 1, 200);

        assertThat(context.turnCount()).isEqualTo(2);
        assertThat(context.truncated()).isTrue();
        assertThat(context.messages())
                .extracting(message -> ((ChatMessage.TextContent) message.content()).text())
                .containsExactly("第二轮用户", "第二轮回答");
    }

    private ConversationTurnRecord completed(long index, String user, String assistant) {
        return new ConversationTurnRecord(
                UUID.randomUUID(),
                CONVERSATION_ID,
                index,
                user,
                assistant,
                UUID.randomUUID(),
                ConversationTurnStatus.COMPLETED,
                null,
                NOW,
                NOW);
    }
}
