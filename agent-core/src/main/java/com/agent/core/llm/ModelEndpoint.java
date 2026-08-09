package com.agent.core.llm;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.Objects;

/**
 * 模型端点与其独立熔断器。
 *
 * @param name           端点名称
 * @param model          模型名称
 * @param client         LLM 客户端
 * @param circuitBreaker 端点熔断器
 */
public record ModelEndpoint(
        String name,
        String model,
        LlmClient client,
        CircuitBreaker circuitBreaker,
        InferenceServiceContract serviceContract) {

    /** 保留已有调用方的 OpenAI 兼容构造器。 */
    public ModelEndpoint(
            String name,
            String model,
            LlmClient client,
            CircuitBreaker circuitBreaker) {
        this(name, model, client, circuitBreaker, new InferenceServiceContract(
                name,
                model,
                InferenceProtocol.OPENAI_CHAT_COMPLETIONS,
                InferenceServiceContract.allCapabilities()));
    }

    /** 校验端点配置。 */
    public ModelEndpoint {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        Objects.requireNonNull(client, "client 不能为空");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker 不能为空");
        Objects.requireNonNull(serviceContract, "serviceContract 不能为空");
        if (!name.equals(serviceContract.endpointName())) {
            throw new IllegalArgumentException("serviceContract.endpointName 必须与 name 一致");
        }
        if (!model.equals(serviceContract.model())) {
            throw new IllegalArgumentException("serviceContract.model 必须与 model 一致");
        }
    }
}
