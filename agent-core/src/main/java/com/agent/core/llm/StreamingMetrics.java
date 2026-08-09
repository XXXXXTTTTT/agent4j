package com.agent.core.llm;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** 一次成功 SSE 推理请求的背压与延迟指标。 */
public record StreamingMetrics(
        int httpStatus,
        Optional<Duration> timeToFirstChunk,
        Duration totalDuration,
        long chunkCount,
        Duration consumerBackpressureDuration,
        Duration maxConsumerBackpressureDuration) {

    /** 校验指标不变量。 */
    public StreamingMetrics {
        if (httpStatus <= 0) {
            throw new IllegalArgumentException("httpStatus 必须大于 0");
        }
        timeToFirstChunk = Objects.requireNonNull(
                timeToFirstChunk, "timeToFirstChunk 不能为空");
        Objects.requireNonNull(totalDuration, "totalDuration 不能为空");
        Objects.requireNonNull(
                consumerBackpressureDuration,
                "consumerBackpressureDuration 不能为空");
        Objects.requireNonNull(
                maxConsumerBackpressureDuration,
                "maxConsumerBackpressureDuration 不能为空");
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount 不能为负数");
        }
    }
}
