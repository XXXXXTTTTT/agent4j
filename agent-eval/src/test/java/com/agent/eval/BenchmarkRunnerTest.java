package com.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkRunnerTest {

    private static final Instant BASE = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void executesEveryRepetitionWithConfiguredConcurrencyAndStableOrder() {
        BenchmarkTaskSet taskSet = tasks();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        BenchmarkTaskExecutor executor = (task, repetition, timeout) -> {
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);
            calls.incrementAndGet();
            try {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                Instant started = BASE.plusMillis(repetition);
                return new BenchmarkTaskResult(task.id(), repetition, true, started,
                        java.util.Optional.of(started.plusMillis(2)), started.plusMillis(3), null);
            } finally {
                active.decrementAndGet();
            }
        };

        try (BenchmarkRunner runner = new BenchmarkRunner(executor)) {
            BenchmarkReport report = runner.run(new BenchmarkRunRequest(
                    taskSet, 2, 3, Duration.ofSeconds(2)));
            assertThat(calls).hasValue(100);
            assertThat(maximum).hasValueLessThanOrEqualTo(3);
            assertThat(report.results()).hasSize(100);
            assertThat(report.results().get(0).taskId()).isEqualTo("task-00");
            assertThat(report.results().get(0).repetition()).isEqualTo(1);
            assertThat(report.passK()).isEqualTo(1.0);
        }
    }

    @Test
    void convertsExecutorExceptionToFailureWithCompleteStack() {
        try (BenchmarkRunner runner = new BenchmarkRunner((task, repetition, timeout) -> {
            throw new IllegalStateException("boom-" + task.id());
        })) {
            BenchmarkReport report = runner.run(new BenchmarkRunRequest(
                    tasks(), 1, 4, Duration.ofSeconds(1)));
            assertThat(report.failedExecutionCount()).isEqualTo(50);
            assertThat(report.results()).allSatisfy(result -> {
                assertThat(result.passed()).isFalse();
                assertThat(result.failureStack()).contains("IllegalStateException", "boom-");
            });
        }
    }

    @Test
    void rejectsRunsAfterClose() {
        BenchmarkRunner runner = new BenchmarkRunner((task, repetition, timeout) -> null);
        runner.close();
        assertThatThrownBy(() -> runner.run(new BenchmarkRunRequest(
                tasks(), 1, 1, Duration.ofSeconds(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关闭");
    }

    @Test
    void writesUtf8ReportThatCanBeReadBack() throws Exception {
        BenchmarkTaskSet taskSet = tasks();
        try (BenchmarkRunner runner = new BenchmarkRunner((task, repetition, timeout) -> {
            Instant started = BASE;
            return new BenchmarkTaskResult(task.id(), repetition, true, started,
                    java.util.Optional.empty(), started.plusMillis(1), null);
        })) {
            BenchmarkReport report = runner.run(new BenchmarkRunRequest(
                    taskSet, 1, 2, Duration.ofSeconds(1)));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new BenchmarkReportWriter().write(report, output);
            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .registerModule(new Jdk8Module());
            BenchmarkReport restored = mapper.readValue(output.toByteArray(), BenchmarkReport.class);
            assertThat(restored.results()).hasSize(50);
            assertThat(restored.passK()).isEqualTo(1.0);
            assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                    .contains("task-00");
        }
    }

    private BenchmarkTaskSet tasks() {
        return new BenchmarkTaskSet(java.util.stream.IntStream.range(0, 50)
                .mapToObj(index -> new BenchmarkTask(
                        "task-%02d".formatted(index), "CODE", "prompt", "criteria", Map.of()))
                .toList());
    }
}
