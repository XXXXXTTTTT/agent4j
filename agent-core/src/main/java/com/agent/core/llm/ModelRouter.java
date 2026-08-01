package com.agent.core.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 根据任务类型执行端点熔断与顺序降级的模型路由器。
 */
public final class ModelRouter {

    private final Map<TaskType, List<ModelEndpoint>> routes;

    /**
     * 使用调用方注入的完整路由创建路由器。
     *
     * @param routes 每种任务对应的有序端点列表
     */
    public ModelRouter(Map<TaskType, List<ModelEndpoint>> routes) {
        Objects.requireNonNull(routes, "routes 不能为空");
        EnumMap<TaskType, List<ModelEndpoint>> copiedRoutes =
                new EnumMap<>(TaskType.class);
        for (TaskType taskType : TaskType.values()) {
            List<ModelEndpoint> endpoints = routes.get(taskType);
            if (endpoints == null || endpoints.isEmpty()) {
                throw new IllegalArgumentException(taskType + " 路由不能为空");
            }
            copiedRoutes.put(taskType, List.copyOf(endpoints));
        }
        this.routes = Collections.unmodifiableMap(copiedRoutes);
    }

    /**
     * 按任务路由执行模型请求，并在端点失败时严格按列表顺序降级。
     *
     * @param taskType 精确任务类型
     * @param request  不含模型名的请求
     * @return 实际成功端点与响应
     */
    public RoutedCompletion complete(TaskType taskType, ModelRequest request) {
        Objects.requireNonNull(taskType, "taskType 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        List<ModelEndpointException> failures = new ArrayList<>();

        for (ModelEndpoint endpoint : routes.get(taskType)) {
            try {
                LlmClient.ChatCompletionResponse response = endpoint.circuitBreaker()
                        .executeSupplier(() -> validatedComplete(endpoint, request));
                return new RoutedCompletion(endpoint.name(), endpoint.model(), response);
            } catch (RuntimeException exception) {
                failures.add(new ModelEndpointException(
                        endpoint.name(), endpoint.model(), exception));
            }
        }

        ModelRoutingException routingException = new ModelRoutingException(taskType);
        failures.forEach(routingException::addSuppressed);
        throw routingException;
    }

    private LlmClient.ChatCompletionResponse validatedComplete(
            ModelEndpoint endpoint,
            ModelRequest request) {
        LlmClient.ChatCompletionRequest completionRequest =
                new LlmClient.ChatCompletionRequest(
                        endpoint.model(),
                        request.messages(),
                        request.tools(),
                        request.toolChoice(),
                        request.temperature(),
                        false);
        LlmClient.ChatCompletionResponse response = Objects.requireNonNull(
                endpoint.client().complete(completionRequest), "模型响应不能为空");
        if (response.choices().isEmpty()) {
            throw new IllegalStateException("模型响应 choices 不能为空");
        }
        LlmClient.Choice firstChoice = Objects.requireNonNull(
                response.choices().getFirst(), "模型响应第一项 choice 不能为空");
        Objects.requireNonNull(firstChoice.message(), "模型响应第一项 message 不能为空");
        return response;
    }
}
