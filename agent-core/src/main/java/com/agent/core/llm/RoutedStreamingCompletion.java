package com.agent.core.llm;

import java.util.Objects;

/** 实际完成流式请求的端点和背压指标。 */
public record RoutedStreamingCompletion(
        String endpointName,
        String model,
        StreamingMetrics metrics) {

    /** 校验流式路由结果。 */
    public RoutedStreamingCompletion {
        if (endpointName == null || endpointName.isBlank()) {
            throw new IllegalArgumentException("endpointName 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        Objects.requireNonNull(metrics, "metrics 不能为空");
    }
}
