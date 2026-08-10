package com.agent.web.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditTextRedactorTest {

    @Test
    void redactsConfiguredAndEmbeddedCredentialsWithoutRemovingConversationText() {
        AuditTextRedactor redactor = new AuditTextRedactor(List.of("configured-secret-value"));

        String result = redactor.redact(
                "请检查 configured-secret-value、Bearer abc.def、sk-test123，"
                        + "password=plain-text，OPENAI_API_KEY=env-value，"
                        + "JSON: {\"token\":\"json secret with spaces\"}");

        assertThat(result)
                .isEqualTo("请检查 [REDACTED]、Bearer [REDACTED]、[REDACTED]，"
                        + "password=[REDACTED]，OPENAI_API_KEY=[REDACTED]，"
                        + "JSON: {\"token\":\"[REDACTED]\"}")
                .doesNotContain(
                        "configured-secret-value", "abc.def", "sk-test123",
                        "plain-text", "env-value", "json secret with spaces");
    }
}
