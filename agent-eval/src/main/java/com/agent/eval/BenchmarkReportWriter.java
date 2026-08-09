package com.agent.eval;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 将 Benchmark 报告稳定序列化为 UTF-8 JSON。 */
public final class BenchmarkReportWriter {

    private final ObjectMapper objectMapper;

    public BenchmarkReportWriter() {
        this(new ObjectMapper());
    }

    public BenchmarkReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空")
                .copy()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module())
                .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** 写入输出流但不关闭调用方持有的流。 */
    public void write(BenchmarkReport report, OutputStream output) {
        Objects.requireNonNull(report, "report 不能为空");
        Objects.requireNonNull(output, "output 不能为空");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, report);
        } catch (IOException exception) {
            throw new IllegalArgumentException("写入 Benchmark 报告失败", exception);
        }
    }

    /** 以 UTF-8 写入目标文件。 */
    public void write(BenchmarkReport report, Path path) {
        Objects.requireNonNull(path, "path 不能为空");
        try (OutputStream output = Files.newOutputStream(path)) {
            write(report, output);
        } catch (IOException exception) {
            throw new IllegalArgumentException("写入 Benchmark 报告失败", exception);
        }
    }

    /** 写入不包含 Prompt、密钥和完整回答的 Evaluation 审计信封。 */
    public void write(
            EvaluationReport report,
            EvaluationGateResult gate,
            EvaluationMode mode,
            int modelCallAttempts,
            OutputStream output) {
        Objects.requireNonNull(report, "report 不能为空");
        Objects.requireNonNull(gate, "gate 不能为空");
        Objects.requireNonNull(mode, "mode 不能为空");
        Objects.requireNonNull(output, "output 不能为空");
        if (modelCallAttempts < 0) {
            throw new IllegalArgumentException("modelCallAttempts 不能为负数");
        }
        EvaluationEnvelope envelope = envelope(
                report, gate, mode, modelCallAttempts);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, envelope);
        } catch (IOException exception) {
            throw new IllegalArgumentException("写入 Evaluation 报告失败", exception);
        }
    }

    /** 以 UTF-8 写入 Evaluation 审计信封。 */
    public void write(
            EvaluationReport report,
            EvaluationGateResult gate,
            EvaluationMode mode,
            int modelCallAttempts,
            Path path) {
        Objects.requireNonNull(path, "path 不能为空");
        try (OutputStream output = Files.newOutputStream(path)) {
            write(report, gate, mode, modelCallAttempts, output);
        } catch (IOException exception) {
            throw new IllegalArgumentException("写入 Evaluation 报告失败", exception);
        }
    }

    private EvaluationEnvelope envelope(
            EvaluationReport report,
            EvaluationGateResult gate,
            EvaluationMode mode,
            int modelCallAttempts) {
        Map<FailureCategory, Integer> failures = new LinkedHashMap<>();
        for (FailureCategory category : FailureCategory.values()) {
            failures.put(category, report.failureCounts().getOrDefault(category, 0));
        }
        BenchmarkReport benchmark = report.benchmarkReport();
        return new EvaluationEnvelope(
                report.suiteId(), mode.name().toLowerCase(Locale.ROOT), modelCallAttempts,
                report.capabilities(),
                new EvaluationMetrics(
                        benchmark.passK(), benchmark.taskCount(), benchmark.passedTaskCount(),
                        benchmark.failedExecutionCount(), benchmark.ttft()),
                new TokenMetrics(report.totalInputTokens(), report.totalOutputTokens()),
                report.totalCostUsd(), failures, gate, report.generatedAt());
    }

    private record EvaluationEnvelope(
            String suiteId,
            String mode,
            int modelCallAttempts,
            List<EvaluationReport.CapabilityMetrics> capabilities,
            EvaluationMetrics metrics,
            TokenMetrics tokens,
            java.math.BigDecimal cost,
            Map<FailureCategory, Integer> failures,
            EvaluationGateResult gate,
            Instant generatedAt) {
    }

    private record EvaluationMetrics(
            double passK,
            int taskCount,
            int passedTaskCount,
            int failedExecutionCount,
            BenchmarkReport.TtftMetrics ttft) {
    }

    private record TokenMetrics(long input, long output) {
    }
}
