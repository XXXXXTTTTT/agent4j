package com.agent.web.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Slf4jConversationAuditSinkTest {

    @Test
    void writesStructuredAuditJsonUsingChinaStandardTime() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(Slf4jConversationAuditSink.LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            UUID conversationId = UUID.randomUUID();
            new Slf4jConversationAuditSink(
                    new ObjectMapper(),
                    new AuditTextRedactor(java.util.List.of("exact-api-key"))).record(
                    new ConversationAuditEvent(
                            ConversationAuditEventType.CONVERSATION_TURN_COMPLETED,
                            Instant.parse("2026-08-10T01:02:03Z"),
                            "local",
                            UUID.randomUUID(),
                            conversationId,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            2L,
                            "COMPLETED",
                            "用户问题 exact-api-key",
                            "Agent 回答 Bearer response-token",
                            null,
                            1250L));

            assertThat(appender.list).hasSize(1);
            JsonNode json = new ObjectMapper().readTree(appender.list.getFirst().getFormattedMessage());
            assertThat(json.path("eventType").asText()).isEqualTo("CONVERSATION_TURN_COMPLETED");
            assertThat(json.path("occurredAt").asText()).isEqualTo("2026-08-10T09:02:03.000+08:00");
            assertThat(json.path("conversationId").asText()).isEqualTo(conversationId.toString());
            assertThat(json.path("userContent").asText()).isEqualTo("用户问题 [REDACTED]");
            assertThat(json.path("assistantContent").asText())
                    .isEqualTo("Agent 回答 Bearer [REDACTED]");
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .doesNotContain("exact-api-key", "response-token");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
