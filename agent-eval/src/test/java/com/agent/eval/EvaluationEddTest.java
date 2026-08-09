package com.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationEddTest {

    @Test
    void evaluatesChapterCapabilitiesAndWritesCiGateReport() throws Exception {
        Instant started = Instant.parse("2026-08-09T00:00:00Z");
        List<BenchmarkTask> tasks = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        List<BenchmarkTaskResult> results = new ArrayList<>();
        List<EvaluationObservation> observations = new ArrayList<>();
        for (int index = 1; index <= 50; index++) {
            String capability = switch (index % 3) {
                case 0 -> "cli";
                case 1 -> "gui";
                default -> "rag";
            };
            String taskId = capability + "-" + index;
            tasks.add(new BenchmarkTask(taskId, capability, "prompt", "criteria", Map.of()));
            mapping.put(taskId, capability);
            results.add(new BenchmarkTaskResult(
                    taskId, 1, true, started, Optional.of(started.plusMillis(20)),
                    started.plusMillis(50), null));
            observations.add(new EvaluationObservation(
                    taskId, 1, trace(capability), 20, 10,
                    new BigDecimal("0.0100"), FailureCategory.NONE, ""));
        }
        BenchmarkTaskSet taskSet = new BenchmarkTaskSet(tasks);
        List<EvaluationCapability> capabilities = List.of(
                capability("cli", List.of("planner", "coder", "ops")),
                capability("gui", List.of("planner", "gui")),
                capability("rag", List.of("planner", "knowledge")));
        EvaluationSuite suite = new EvaluationSuite(
                "chapter-23", taskSet, mapping, capabilities,
                new EvaluationGatePolicy(1.0, Duration.ofSeconds(1),
                        BigDecimal.ONE, 0));

        EvaluationReport report = EvaluationScorer.score(suite, 1, results, observations);
        EvaluationGateResult gate = EvaluationGate.evaluate(report);
        Path path = Path.of("target", "edd", "evaluation-chapter-23.json");
        Files.createDirectories(path.getParent());
        new BenchmarkReportWriter().write(
                report, gate, EvaluationMode.DETERMINISTIC, 0, path);

        JsonNode written = new ObjectMapper().findAndRegisterModules().readTree(path.toFile());
        assertThat(gate.passed()).isTrue();
        assertThat(written.path("capabilities")).hasSize(3);
        assertThat(written.path("mode").textValue()).isEqualTo("deterministic");
        assertThat(written.path("modelCallAttempts").intValue()).isZero();
    }

    private EvaluationCapability capability(String id, List<String> trace) {
        return new EvaluationCapability(id, "23", trace, 1.0,
                Duration.ofSeconds(1), new BigDecimal("0.1000"));
    }

    private List<String> trace(String capability) {
        return switch (capability) {
            case "cli" -> List.of("planner", "coder", "ops");
            case "gui" -> List.of("planner", "gui");
            case "rag" -> List.of("planner", "knowledge");
            default -> throw new IllegalArgumentException("未知能力: " + capability);
        };
    }
}
