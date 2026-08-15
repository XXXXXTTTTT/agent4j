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

class Slf4jWorkspaceFileAuditSinkTest {

    @Test
    void writesStructuredAuditJsonUsingChinaStandardTime() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.agent.audit.workspace");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new Slf4jWorkspaceFileAuditSink(new ObjectMapper()).record(
                    new WorkspaceFileAuditEvent(
                            WorkspaceFileAuditEventType.FILE_READ,
                            Instant.parse("2026-08-10T01:02:03Z"),
                            "local", UUID.randomUUID(), "src/Main.java", 12,
                            "sha", "SUCCESS"));

            assertThat(appender.list).hasSize(1);
            JsonNode json = new ObjectMapper().readTree(appender.list.getFirst().getFormattedMessage());
            assertThat(json.path("occurredAt").asText())
                    .isEqualTo("2026-08-10T09:02:03.000+08:00");
            assertThat(json.path("path").asText()).isEqualTo("src/Main.java");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
