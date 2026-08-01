package com.agent.core.llm;

import java.util.Objects;

/**
 * 路由完成结果。
 *
 * @param endpointName 实际端点名称
 * @param model        实际模型名称
 * @param response     模型响应
 */
public record RoutedCompletion(
        String endpointName,
        String model,
        LlmClient.ChatCompletionResponse response) {

    /** 校验路由结果。 */
    public RoutedCompletion {
        if (endpointName == null || endpointName.isBlank()) {
            throw new IllegalArgumentException("endpointName 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        Objects.requireNonNull(response, "response 不能为空");
    }
}
