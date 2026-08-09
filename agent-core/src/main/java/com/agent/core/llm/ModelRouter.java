package com.agent.core.llm;

import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.engine.ExecutionBudgetExceededException;
import com.agent.core.observability.ModelCallObserver;
import com.agent.core.observability.ModelCallSpan;
import com.agent.core.observability.ModelCallStart;
import com.agent.core.observability.ModelCallSuccess;
import com.agent.core.observability.ModelUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 根据任务类型执行端点熔断与顺序降级的模型路由器。
 */
public final class ModelRouter {

    private static final System.Logger LOGGER =
            System.getLogger(ModelRouter.class.getName());

    private final Map<TaskType, List<ModelEndpoint>> routes;
    private final ModelCallObserver modelCallObserver;

    /**
     * 使用调用方注入的完整路由创建路由器。
     *
     * @param routes 每种任务对应的有序端点列表
     */
    public ModelRouter(Map<TaskType, List<ModelEndpoint>> routes) {
        this(routes, ModelCallObserver.noop());
    }

    /**
     * 使用完整路由和模型调用观测器创建路由器。
     *
     * @param routes            每种任务对应的有序端点列表
     * @param modelCallObserver 模型端点调用观测器
     */
    public ModelRouter(
            Map<TaskType, List<ModelEndpoint>> routes,
            ModelCallObserver modelCallObserver) {
        Objects.requireNonNull(routes, "routes 不能为空");
        this.modelCallObserver = Objects.requireNonNull(
                modelCallObserver, "modelCallObserver 不能为空");
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
            ModelCallSpan span = startSpan(new ModelCallStart(
                    NodeExecutionContext.current(),
                    taskType,
                    endpoint.name(),
                    endpoint.model()));
            try {
                requireCapabilities(endpoint, requiredCapabilities(taskType, request));
                LlmClient.ChatCompletionResponse response;
                try (InferencePermit ignored = endpoint.admissionController().acquire()) {
                    response = endpoint.circuitBreaker()
                            .executeSupplier(() -> validatedComplete(endpoint, request));
                }
                succeedSpan(span, response);
                recordTokenUsage(response);
                return new RoutedCompletion(endpoint.name(), endpoint.model(), response);
            } catch (ExecutionBudgetExceededException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                failSpan(span, exception);
                failures.add(new ModelEndpointException(
                        endpoint.name(), endpoint.model(), exception));
            } finally {
                closeSpan(span);
            }
        }

        ModelRoutingException routingException = new ModelRoutingException(taskType);
        failures.forEach(routingException::addSuppressed);
        throw routingException;
    }

    /** 按任务路由执行 SSE 请求，并返回实际端点和流式指标。 */
    public RoutedStreamingCompletion stream(
            TaskType taskType,
            ModelRequest request,
            Consumer<LlmClient.ChatCompletionChunk> consumer) {
        Objects.requireNonNull(taskType, "taskType 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(consumer, "consumer 不能为空");
        List<ModelEndpointException> failures = new ArrayList<>();
        EnumSet<InferenceCapability> required = requiredCapabilities(taskType, request);
        required.add(InferenceCapability.STREAMING);

        for (ModelEndpoint endpoint : routes.get(taskType)) {
            ModelCallSpan span = startSpan(new ModelCallStart(
                    NodeExecutionContext.current(),
                    taskType,
                    endpoint.name(),
                    endpoint.model()));
            try {
                requireCapabilities(endpoint, required);
                StreamingMetrics metrics;
                try (InferencePermit ignored = endpoint.admissionController().acquire()) {
                    metrics = endpoint.circuitBreaker().executeSupplier(
                            () -> validatedStream(endpoint, request, consumer));
                }
                succeedStreamingSpan(span, endpoint.model());
                return new RoutedStreamingCompletion(
                        endpoint.name(), endpoint.model(), metrics);
            } catch (ExecutionBudgetExceededException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                failSpan(span, exception);
                failures.add(new ModelEndpointException(
                        endpoint.name(), endpoint.model(), exception));
            } finally {
                closeSpan(span);
            }
        }

        ModelRoutingException routingException = new ModelRoutingException(taskType);
        failures.forEach(routingException::addSuppressed);
        throw routingException;
    }

    private EnumSet<InferenceCapability> requiredCapabilities(
            TaskType taskType,
            ModelRequest request) {
        EnumSet<InferenceCapability> required = EnumSet.of(
                InferenceCapability.CHAT_COMPLETIONS);
        if (!request.tools().isEmpty()) {
            required.add(InferenceCapability.TOOL_CALLING);
        }
        if (taskType == TaskType.VISION) {
            required.add(InferenceCapability.VISION_INPUT);
        }
        return required;
    }

    private void requireCapabilities(
            ModelEndpoint endpoint,
            EnumSet<InferenceCapability> required) {
        EnumSet<InferenceCapability> missing = EnumSet.copyOf(required);
        missing.removeAll(endpoint.serviceContract().capabilities());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "模型端点缺少必需能力: " + missing);
        }
    }

    private void recordTokenUsage(LlmClient.ChatCompletionResponse response) {
        LlmClient.Usage usage = response.usage();
        if (usage != null && NodeExecutionContext.current().isPresent()) {
            NodeExecutionContext.consumeTokens(usage.totalTokens());
        }
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

    private StreamingMetrics validatedStream(
            ModelEndpoint endpoint,
            ModelRequest request,
            Consumer<LlmClient.ChatCompletionChunk> consumer) {
        LlmClient.ChatCompletionRequest completionRequest =
                new LlmClient.ChatCompletionRequest(
                        endpoint.model(),
                        request.messages(),
                        request.tools(),
                        request.toolChoice(),
                        request.temperature(),
                        true);
        return Objects.requireNonNull(
                endpoint.client().stream(completionRequest, consumer),
                "流式模型指标不能为空");
    }

    private ModelCallSpan startSpan(ModelCallStart start) {
        try {
            return Objects.requireNonNull(
                    modelCallObserver.start(start),
                    "modelCallObserver 返回的 span 不能为空");
        } catch (RuntimeException exception) {
            logObserverFailure("模型调用观测器启动失败", exception);
            return ModelCallObserver.noop().start(start);
        }
    }

    private void succeedSpan(
            ModelCallSpan span,
            LlmClient.ChatCompletionResponse response) {
        try {
            span.succeed(new ModelCallSuccess(
                    Optional.ofNullable(response.model())
                            .filter(model -> !model.isBlank()),
                    Optional.ofNullable(response.usage())
                            .map(usage -> new ModelUsage(
                                    usage.promptTokens(),
                                    usage.completionTokens(),
                                    usage.totalTokens()))));
        } catch (RuntimeException exception) {
            logObserverFailure("模型调用成功观测失败", exception);
        }
    }

    private void succeedStreamingSpan(ModelCallSpan span, String model) {
        try {
            span.succeed(new ModelCallSuccess(
                    Optional.of(model), Optional.empty()));
        } catch (RuntimeException exception) {
            logObserverFailure("流式模型调用成功观测失败", exception);
        }
    }

    private void failSpan(ModelCallSpan span, RuntimeException failure) {
        try {
            span.fail(failure);
        } catch (RuntimeException exception) {
            logObserverFailure("模型调用失败观测失败", exception);
        }
    }

    private void closeSpan(ModelCallSpan span) {
        try {
            span.close();
        } catch (RuntimeException exception) {
            logObserverFailure("模型调用观测器关闭失败", exception);
        }
    }

    private void logObserverFailure(String message, RuntimeException exception) {
        LOGGER.log(System.Logger.Level.ERROR, message, exception);
    }
}
