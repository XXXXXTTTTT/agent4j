package com.agent.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 单次任务执行结果和首 Token 时间线。 */
public record BenchmarkTaskResult(
        String taskId,
        int repetition,
        boolean passed,
        Instant startedAt,
        Optional<Instant> firstTokenAt,
        Instant finishedAt,
        String failureStack) {

    public BenchmarkTaskResult {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (repetition < 1) {
            throw new IllegalArgumentException("repetition 必须大于 0");
        }
        Objects.requireNonNull(startedAt, "startedAt 不能为空");
        firstTokenAt = Objects.requireNonNull(firstTokenAt, "firstTokenAt 不能为空");
        finishedAt = Objects.requireNonNull(finishedAt, "finishedAt 不能为空");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt 不能早于 startedAt");
        }
        if (firstTokenAt.isPresent()
                && (firstTokenAt.orElseThrow().isBefore(startedAt)
                || firstTokenAt.orElseThrow().isAfter(finishedAt))) {
            throw new IllegalArgumentException("firstTokenAt 必须位于执行时间线内");
        }
        if (passed && failureStack != null && !failureStack.isBlank()) {
            throw new IllegalArgumentException("通过结果不能携带 failureStack");
        }
        if (!passed && (failureStack == null || failureStack.isBlank())) {
            throw new IllegalArgumentException("失败结果必须携带 failureStack");
        }
    }

    /** 返回可选的首字延迟。 */
    public Optional<Duration> ttft() {
        return firstTokenAt.map(value -> Duration.between(startedAt, value));
    }
}
