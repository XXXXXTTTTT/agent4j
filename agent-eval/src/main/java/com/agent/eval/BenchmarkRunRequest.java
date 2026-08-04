package com.agent.eval;

import java.time.Duration;
import java.util.Objects;

/** 一次 Benchmark 执行配置。 */
public record BenchmarkRunRequest(
        BenchmarkTaskSet taskSet,
        int repetitions,
        int maxConcurrency,
        Duration timeout) {

    public BenchmarkRunRequest {
        Objects.requireNonNull(taskSet, "taskSet 不能为空");
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions 必须大于 0");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency 必须大于 0");
        }
        timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }
}
