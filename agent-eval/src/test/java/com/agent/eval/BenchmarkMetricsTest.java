package com.agent.eval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkMetricsTest {

    private static final Instant BASE = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void calculatesPassKForSingleRepetition() {
        BenchmarkTaskSet tasks = tasks("one", "two");

        BenchmarkReport report = BenchmarkMetrics.calculate(tasks, 1, complete(tasks, 1, List.of(
                result("two", 1, false, 20, null, "java.lang.IllegalStateException: boom"),
                result("one", 1, true, 10, 15, null))));

        assertThat(report.passK()).isEqualTo(49.0 / 50.0);
        assertThat(report.passedTaskCount()).isEqualTo(49);
        assertThat(report.failedExecutionCount()).isEqualTo(1);
        assertThat(report.taskMetrics()).first().satisfies(metric -> {
            assertThat(metric.taskId()).isEqualTo("one");
            assertThat(metric.passedCount()).isEqualTo(1);
            assertThat(metric.failedCount()).isZero();
            assertThat(metric.failureStacks()).isEmpty();
        });
        assertThat(report.taskMetrics()).element(49).satisfies(metric -> {
            assertThat(metric.taskId()).isEqualTo("two");
            assertThat(metric.passedCount()).isZero();
            assertThat(metric.failedCount()).isEqualTo(1);
            assertThat(metric.failureStacks()).singleElement()
                    .asString().contains("IllegalStateException");
        });
    }

    @Test
    void calculatesPassKForThreeRepetitionsAndStableResultOrder() {
        BenchmarkTaskSet tasks = tasks("a", "b");
        List<BenchmarkTaskResult> results = List.of(
                result("b", 3, true, 0, 30, null),
                result("a", 2, true, 0, 20, null),
                result("b", 1, true, 0, 10, null),
                result("a", 3, false, 0, null, "failure"),
                result("a", 1, true, 0, 10, null),
                result("b", 2, true, 0, 20, null));

        BenchmarkReport report = BenchmarkMetrics.calculate(tasks, 3, complete(tasks, 3, results));

        assertThat(report.passK()).isEqualTo(49.0 / 50.0);
        assertThat(report.results().subList(0, 6)).extracting(BenchmarkTaskResult::taskId,
                        BenchmarkTaskResult::repetition)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a", 1),
                        org.assertj.core.groups.Tuple.tuple("a", 2),
                        org.assertj.core.groups.Tuple.tuple("a", 3),
                        org.assertj.core.groups.Tuple.tuple("b", 1),
                        org.assertj.core.groups.Tuple.tuple("b", 2),
                        org.assertj.core.groups.Tuple.tuple("b", 3));
    }

    @Test
    void rejectsMissingDuplicateAndUnknownResults() {
        BenchmarkTaskSet tasks = tasks("one");
        assertThatThrownBy(() -> BenchmarkMetrics.calculate(tasks, 3,
                List.of(result("one", 1, true, 0, 1, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> BenchmarkMetrics.calculate(tasks, 1,
                List.of(result("other", 1, true, 0, 1, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务");
    }

    @Test
    void calculatesTtftAveragePercentilesAndMaxInMilliseconds() {
        BenchmarkTaskSet tasks = tasks("one");
        BenchmarkReport report = BenchmarkMetrics.calculate(tasks, 3, complete(tasks, 3, List.of(
                result("one", 1, true, 0, 10, null),
                result("one", 2, false, 0, null, "failure"),
                result("one", 3, true, 0, 30, null))));

        assertThat(report.ttft().count()).isEqualTo(2);
        assertThat(report.ttft().averageMs()).isEqualTo(20.0);
        assertThat(report.ttft().p50Ms()).isEqualTo(20.0);
        assertThat(report.ttft().p95Ms()).isEqualTo(29.0);
        assertThat(report.ttft().maxMs()).isEqualTo(30.0);
    }

    @Test
    void rejectsInvalidTimelineBeforeAggregation() {
        assertThatThrownBy(() -> new BenchmarkTaskResult(
                "one", 1, true, BASE, Optional.of(BASE.minusNanos(1)),
                BASE.plusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时间线");
    }

    @Test
    void reportRejectsIncompleteOrInconsistentAggregateFields() {
        BenchmarkReport.TtftMetrics emptyTtft =
                new BenchmarkReport.TtftMetrics(0, 0, 0, 0, 0);
        assertThatThrownBy(() -> new BenchmarkReport(
                List.of(), List.of(), 1, 1, 0, 0, 0, emptyTtft, BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结果");
        BenchmarkTaskResult passed = result("one", 1, true, 0, null, null);
        assertThatThrownBy(() -> new BenchmarkReport(
                List.of(passed),
                List.of(new BenchmarkReport.TaskMetrics("one", 1, 0, List.of())),
                1, 1, 0, 0, 1, emptyTtft, BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("聚合");
    }

    private BenchmarkTaskSet tasks(String... ids) {
        List<BenchmarkTask> values = java.util.stream.Stream.of(ids)
                .map(id -> new BenchmarkTask(id, "CODE", "prompt", "criteria", Map.of()))
                .toList();
        List<BenchmarkTask> padded = new java.util.ArrayList<>(values);
        for (int i = padded.size(); i < 50; i++) {
            padded.add(new BenchmarkTask("padding-" + i, "CODE", "prompt", "criteria", Map.of()));
        }
        return new BenchmarkTaskSet(padded);
    }

    private BenchmarkTaskResult result(String taskId, int repetition, boolean passed,
                                       long startMs, Integer firstTokenMs, String failure) {
        Instant started = BASE.plusMillis(startMs);
        Instant finished = BASE.plusMillis(Math.max(startMs + 1,
                firstTokenMs == null ? startMs + 1 : firstTokenMs));
        return new BenchmarkTaskResult(taskId, repetition, passed, started,
                firstTokenMs == null ? Optional.empty()
                        : Optional.of(BASE.plusMillis(firstTokenMs)), finished, failure);
    }

    private List<BenchmarkTaskResult> complete(BenchmarkTaskSet tasks, int repetitions,
                                                List<BenchmarkTaskResult> supplied) {
        List<BenchmarkTaskResult> completed = new java.util.ArrayList<>(supplied);
        for (BenchmarkTask task : tasks.tasks()) {
            if (supplied.stream().noneMatch(result -> result.taskId().equals(task.id()))) {
                for (int repetition = 1; repetition <= repetitions; repetition++) {
                    completed.add(result(task.id(), repetition, true, 0, null, null));
                }
            }
        }
        return completed;
    }
}
