package com.agent.eval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkDomainTest {

    private static final Instant START = Instant.parse("2026-08-05T10:00:00Z");

    @Test
    void freezesTaskMetadataAndTaskSet() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("phase", "1");
        BenchmarkTask task = new BenchmarkTask(
                "task-001", "CODE", "Inspect class", "class extracted", metadata);
        metadata.put("phase", "changed");
        List<BenchmarkTask> source = new ArrayList<>(
                java.util.stream.IntStream.range(0, 50)
                        .mapToObj(index -> index == 0 ? task : task("task-" + index)).toList());
        BenchmarkTaskSet set = new BenchmarkTaskSet(source);
        source.clear();

        assertThat(task.metadata()).containsEntry("phase", "1");
        assertThat(set.tasks()).contains(task);
    }

    @Test
    void requiresAtLeastFiftyUniqueTasksAndPositiveRunConfiguration() {
        assertThatThrownBy(() -> new BenchmarkTaskSet(List.of(task("one"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50");
        List<BenchmarkTask> duplicate = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            duplicate.add(task(index == 49 ? "task-0" : "task-" + index));
        }
        assertThatThrownBy(() -> new BenchmarkTaskSet(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("唯一");
        BenchmarkTaskSet valid = new BenchmarkTaskSet(
                java.util.stream.IntStream.range(0, 50)
                        .mapToObj(index -> task("task-" + index)).toList());
        assertThatThrownBy(() -> new BenchmarkRunRequest(valid, 0, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenchmarkRunRequest(valid, 1, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesResultTimelineAndFailureStack() {
        BenchmarkTaskResult result = new BenchmarkTaskResult(
                "task-001", 1, true, START,
                Optional.of(START.plusMillis(20)), START.plusMillis(100), null);
        assertThat(result.ttft()).hasValue(Duration.ofMillis(20));
        assertThatThrownBy(() -> new BenchmarkTaskResult(
                "task-001", 1, true, START,
                Optional.of(START.minusMillis(1)), START.plusMillis(100), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firstTokenAt");
        assertThatThrownBy(() -> new BenchmarkTaskResult(
                "task-001", 1, false, START,
                Optional.empty(), START.plusMillis(100), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureStack");
    }

    private BenchmarkTask task(String id) {
        return new BenchmarkTask(id, "CODE", "prompt " + id, "criteria " + id, Map.of());
    }
}
