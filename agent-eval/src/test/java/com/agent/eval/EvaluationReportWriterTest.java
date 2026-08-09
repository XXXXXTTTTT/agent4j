package com.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationReportWriterTest {

    @Test
    void writesAuditableEvaluationEnvelopeWithoutRawPrompts() throws Exception {
        EvaluationReport report = EvaluationTestFixture.report(
                new EvaluationGatePolicy(0.9, Duration.ofSeconds(1),
                        new BigDecimal("10"), 1), true);
        EvaluationGateResult gate = EvaluationGate.evaluate(report);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new BenchmarkReportWriter().write(
                report, gate, EvaluationMode.DETERMINISTIC, 0, output);

        String json = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.path("suiteId").textValue()).isEqualTo("chapter-23");
        assertThat(root.path("mode").textValue()).isEqualTo("deterministic");
        assertThat(root.path("modelCallAttempts").intValue()).isZero();
        assertThat(root.path("capabilities")).hasSize(1);
        assertThat(root.path("gate").path("passed").booleanValue()).isTrue();
        assertThat(json).doesNotContain("prompt", "Bearer ", "sk-secret");
    }
}
