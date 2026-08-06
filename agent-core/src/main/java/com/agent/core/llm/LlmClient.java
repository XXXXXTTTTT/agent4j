package com.agent.core.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 基于 Spring RestClient 和 Java 21 虚拟线程的 OpenAI 协议客户端。
 */
public final class LlmClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmClient.class);
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String chatCompletionsPath;
    private final String requestUrl;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建 LLM 客户端。
     *
     * @param restClient          已配置基础地址和鉴权的 RestClient
     * @param objectMapper        JSON 映射器
     * @param chatCompletionsPath Chat Completions API 的精确路径
     */
    public LlmClient(RestClient restClient, ObjectMapper objectMapper, String chatCompletionsPath) {
        this(restClient, objectMapper, chatCompletionsPath, chatCompletionsPath);
    }

    /** 创建带完整审计 URL 的 LLM 客户端。 */
    public LlmClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String chatCompletionsPath,
            String requestUrl) {
        this.restClient = Objects.requireNonNull(restClient, "restClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        if (chatCompletionsPath == null || chatCompletionsPath.isBlank()) {
            throw new IllegalArgumentException("chatCompletionsPath 不能为空");
        }
        this.chatCompletionsPath = chatCompletionsPath;
        if (requestUrl == null || requestUrl.isBlank()) {
            throw new IllegalArgumentException("requestUrl 不能为空");
        }
        this.requestUrl = requestUrl;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 执行一次非流式 Chat Completions 请求。
     *
     * @param request 请求参数
     * @return 完整响应
     */
    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        return runOnVirtualThread(request.model(), () -> {
            long startedAt = System.nanoTime();
            try {
                ChatCompletionResponse response = restClient.post()
                        .uri(chatCompletionsPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(request.withStream(false))
                        .exchange((httpRequest, httpResponse) -> {
                            requireSuccess(httpResponse, "LLM 请求失败");
                            return objectMapper.readValue(
                                    httpResponse.getBody(), ChatCompletionResponse.class);
                        });
                if (response == null) {
                    throw new LlmClientException("LLM 返回了空响应");
                }
                logSuccess(request.model(), response.usage(), 200, startedAt);
                return response;
            } catch (Exception exception) {
                logFailure(request.model(), exception, startedAt);
                throw exception;
            }
        });
    }

    /**
     * 执行一次 SSE 流式 Chat Completions 请求。
     *
     * @param request  请求参数
     * @param consumer 增量响应消费者
     */
    public void stream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> consumer) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(consumer, "consumer 不能为空");
        runOnVirtualThread(request.model(), () -> {
            long startedAt = System.nanoTime();
            AtomicReference<Usage> usage = new AtomicReference<>();
            try {
                Integer status = restClient.post()
                        .uri(chatCompletionsPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .body(request.withStream(true))
                        .exchange((httpRequest, response) -> {
                            int responseStatus = response.getStatusCode().value();
                            consumeSse(response, chunk -> {
                                if (chunk.usage() != null) {
                                    usage.set(chunk.usage());
                                }
                                consumer.accept(chunk);
                            });
                            return responseStatus;
                        });
                logSuccess(request.model(), usage.get(), status == null ? 200 : status, startedAt);
                return null;
            } catch (Exception exception) {
                logFailure(request.model(), exception, startedAt);
                throw exception;
            }
        });
    }

    private void consumeSse(
            ClientHttpResponse response,
            Consumer<ChatCompletionChunk> consumer) throws IOException {
        if (response.getStatusCode().isError()) {
            requireSuccess(response, "LLM SSE 请求失败");
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            StringBuilder eventData = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (dispatchSseEvent(eventData, consumer)) {
                        return;
                    }
                    eventData.setLength(0);
                } else if (line.startsWith(SSE_DATA_PREFIX)) {
                    appendSseData(eventData, line.substring(SSE_DATA_PREFIX.length()));
                }
            }
            dispatchSseEvent(eventData, consumer);
        }
    }

    private void requireSuccess(ClientHttpResponse response, String message)
            throws IOException {
        if (!response.getStatusCode().isError()) {
            return;
        }
        byte[] responseBody = response.getBody().readAllBytes();
        RestClientResponseException httpException = new RestClientResponseException(
                message,
                response.getStatusCode(),
                response.getStatusText(),
                response.getHeaders(),
                responseBody,
                StandardCharsets.UTF_8);
        throw new LlmClientException(
                message + "，HTTP 状态码 " + response.getStatusCode().value(),
                httpException);
    }

    private void logSuccess(
            String model,
            Usage usage,
            int httpStatus,
            long startedAt) {
        int inputTokens = usage == null ? -1 : usage.promptTokens();
        int outputTokens = usage == null ? -1 : usage.completionTokens();
        LOGGER.info(
                "LLM 请求完成 url={} model={} inputTokens={} outputTokens={} httpStatus={} durationMs={}",
                requestUrl,
                model,
                inputTokens,
                outputTokens,
                httpStatus,
                elapsedMillis(startedAt));
    }

    private void logFailure(String model, Throwable failure, long startedAt) {
        RestClientResponseException responseException = findCause(
                failure, RestClientResponseException.class);
        SocketTimeoutException timeoutException = findCause(
                failure, SocketTimeoutException.class);
        int status = responseException == null
                ? -1
                : responseException.getStatusCode().value();
        if (timeoutException != null) {
            LOGGER.warn(
                    "LLM 请求读取超时 url={} model={} httpStatus={} durationMs={}",
                    requestUrl, model, status, elapsedMillis(startedAt), failure);
        } else if (status == 503) {
            LOGGER.warn(
                    "LLM 服务不可用 url={} model={} httpStatus=503 durationMs={}",
                    requestUrl, model, elapsedMillis(startedAt), failure);
        } else {
            LOGGER.error(
                    "LLM 请求失败 url={} model={} httpStatus={} durationMs={}",
                    requestUrl, model, status, elapsedMillis(startedAt), failure);
        }
    }

    private long elapsedMillis(long startedAt) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private void appendSseData(StringBuilder eventData, String rawData) {
        String data = rawData.startsWith(" ") ? rawData.substring(1) : rawData;
        if (!eventData.isEmpty()) {
            eventData.append('\n');
        }
        eventData.append(data);
    }

    private boolean dispatchSseEvent(
            StringBuilder eventData,
            Consumer<ChatCompletionChunk> consumer) throws IOException {
        if (eventData.isEmpty()) {
            return false;
        }
        String data = eventData.toString();
        if (SSE_DONE.equals(data)) {
            return true;
        }
        consumer.accept(objectMapper.readValue(data, ChatCompletionChunk.class));
        return false;
    }

    private <T> T runOnVirtualThread(String modelName, Callable<T> operation) {
        if (closed.get()) {
            throw new LlmClientException("LLM 客户端已经关闭");
        }
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        try {
            Future<T> future = executor.submit(() -> {
                if (callerMdc != null) {
                    MDC.setContextMap(callerMdc);
                }
                MDC.put("modelName", modelName);
                try {
                    return operation.call();
                } finally {
                    MDC.clear();
                }
            });
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("等待 LLM 请求时被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof LlmClientException llmClientException) {
                throw llmClientException;
            }
            throw new LlmClientException("LLM 请求执行失败", cause);
        } catch (RuntimeException exception) {
            throw new LlmClientException("LLM 请求调度失败", exception);
        }
    }

    /**
     * 关闭虚拟线程执行器。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.close();
        }
    }

    /**
     * Chat Completions 请求。
     *
     * @param model       模型名称
     * @param messages    对话消息
     * @param tools       可调用工具
     * @param toolChoice  工具选择策略 JSON
     * @param temperature 采样温度
     * @param stream      是否使用 SSE
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            List<Tool> tools,
            @JsonProperty("tool_choice") JsonNode toolChoice,
            Double temperature,
            boolean stream) {

        /**
         * 校验请求并冻结集合。
         */
        public ChatCompletionRequest {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model 不能为空");
            }
            messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        private ChatCompletionRequest withStream(boolean enabled) {
            return new ChatCompletionRequest(model, messages, tools, toolChoice, temperature, enabled);
        }
    }

    /**
     * 非流式 Chat Completions 响应。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage) {

        /**
         * 冻结响应选项。
         */
        public ChatCompletionResponse {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    /**
     * 非流式响应选项。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            ChatMessage message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    /**
     * Token 用量。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }

    /**
     * OpenAI 工具定义。
     */
    public record Tool(String type, FunctionDefinition function) {

        /**
         * 创建函数工具定义。
         *
         * @param name        函数名称
         * @param description 函数说明
         * @param parameters  JSON Schema 参数
         * @return 函数工具
         */
        public static Tool function(String name, String description, JsonNode parameters) {
            return new Tool("function", new FunctionDefinition(name, description, parameters));
        }

        /**
         * 校验工具定义。
         */
        public Tool {
            Objects.requireNonNull(type, "type 不能为空");
            Objects.requireNonNull(function, "function 不能为空");
        }
    }

    /**
     * 函数工具元数据。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionDefinition(String name, String description, JsonNode parameters) {

        /**
         * 校验函数工具元数据。
         */
        public FunctionDefinition {
            Objects.requireNonNull(name, "name 不能为空");
            Objects.requireNonNull(parameters, "parameters 不能为空");
        }
    }

    /**
     * SSE Chat Completions 增量响应。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionChunk(
            String id,
            String object,
            long created,
            String model,
            List<ChunkChoice> choices,
            Usage usage) {

        /**
         * 冻结增量选项。
         */
        public ChatCompletionChunk {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    /**
     * SSE 响应选项。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkChoice(
            int index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason) {
    }

    /**
     * SSE 增量消息。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(
            ChatMessage.Role role,
            String content,
            @JsonProperty("tool_calls") List<ToolCallDelta> toolCalls) {

        /**
         * 冻结增量工具调用。
         */
        public Delta {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /**
     * SSE 中的工具调用增量。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallDelta(
            Integer index,
            String id,
            String type,
            FunctionCallDelta function) {
    }

    /**
     * SSE 中允许分片到达的函数调用内容。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCallDelta(String name, String arguments) {
    }
}
