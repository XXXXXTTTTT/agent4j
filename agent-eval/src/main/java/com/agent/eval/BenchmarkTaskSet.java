package com.agent.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 经过完整校验的 Benchmark 任务集。 */
public record BenchmarkTaskSet(List<BenchmarkTask> tasks) {

    public BenchmarkTaskSet {
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks 不能为空"));
        if (tasks.size() < 50) {
            throw new IllegalArgumentException("任务集至少需要 50 项");
        }
        if (tasks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("任务集不能包含 null");
        }
        if (new HashSet<>(tasks.stream().map(BenchmarkTask::id).toList()).size()
                != tasks.size()) {
            throw new IllegalArgumentException("任务 ID 必须唯一");
        }
    }
}
