package com.agent.web.persistence;

import com.agent.web.capability.CapabilityManagementAuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class JdbcCapabilityManagementAuditSinkTest {

    @Test
    void rejectsNullEventBeforeOpeningTransaction() {
        JdbcCapabilityManagementAuditSink sink = new JdbcCapabilityManagementAuditSink(
                mock(JdbcClient.class), mock(TransactionTemplate.class), UUID::randomUUID);

        assertThatThrownBy(() -> sink.record(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("event 不能为空");
    }

    @Test
    void retainsAuditEventFields() {
        CapabilityManagementAuditEvent event = new CapabilityManagementAuditEvent(
                "MCP_INSTALLATION_CONFIRMED", "audit-user", UUID.fromString("20552b1f-b193-4a57-ae4f-958697a8e9a8"),
                UUID.fromString("469529f9-ea75-4d1c-ac8e-d01bdf80fabb"), null, null,
                "76d64c822f5125032f89eb71dbdb94e42b434821", "SUCCESS", Instant.parse("2026-08-12T00:00:00Z"));

        assertThat(event.sourceCommitSha()).hasSize(40);
        assertThat(event.result()).isEqualTo("SUCCESS");
    }
}
