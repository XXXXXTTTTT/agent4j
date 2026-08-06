package com.agent.web.conversation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTurnRecordTest {

    private static final UUID CONVERSATION_ID =
            UUID.fromString("3e7b9a98-0cc7-4e2e-96f4-0d3a9a79d9b1");
    private static final UUID TURN_ID =
            UUID.fromString("8d331abd-8dc2-4074-9af9-b0be3b3cb662");
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    @Test
    void requiresAssistantContentForCompletedTurn() {
        assertThatThrownBy(() -> new ConversationTurnRecord(
                TURN_ID,
                CONVERSATION_ID,
                1,
                "用户输入",
                null,
                null,
                ConversationTurnStatus.COMPLETED,
                null,
                NOW,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistantContent");
    }

    @Test
    void requiresErrorForFailedTurn() {
        assertThatThrownBy(() -> new ConversationTurnRecord(
                TURN_ID,
                CONVERSATION_ID,
                1,
                "用户输入",
                null,
                null,
                ConversationTurnStatus.FAILED,
                null,
                NOW,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error");
    }
}
