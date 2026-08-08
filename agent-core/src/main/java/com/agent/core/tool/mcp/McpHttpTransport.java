package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 只接受单个 JSON 响应的 MCP HTTP transport。 */
public final class McpHttpTransport implements McpTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpHttpTransport.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public McpHttpTransport(
            RestClient restClient,
            ObjectMapper objectMapper,
            URI endpoint,
            Duration timeout) {
        RestClient sourceClient = Objects.requireNonNull(restClient, "restClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint 不能为空");
        if (!endpoint.isAbsolute()
                || (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("endpoint 必须是 http 或 https URI");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = sourceClient.mutate()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public McpJsonRpcResponse request(McpJsonRpcRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        if (request.id() == null) {
            throw new IllegalArgumentException("request 必须包含 id");
        }
        HttpReply reply = execute(request, false);
        if (reply.body().isBlank()) {
            throw new McpTransportException("MCP HTTP 响应为空");
        }
        if (!isJson(reply.contentType())) {
            throw new McpTransportException("MCP HTTP 响应 content type 不支持: "
                    + reply.contentType());
        }
        return McpJsonRpcResponse.parse(objectMapper, reply.body(), request.id());
    }

    @Override
    public void notify(McpJsonRpcRequest notification) {
        Objects.requireNonNull(notification, "notification 不能为空");
        if (notification.id() != null) {
            throw new IllegalArgumentException("notification 不能包含 id");
        }
        execute(notification, true);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                LOGGER.warn("MCP HTTP transport 虚拟线程未在限定时间内退出 endpoint={}", endpoint);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("MCP HTTP transport 关闭被中断 endpoint={}", endpoint, exception);
        }
    }

    private HttpReply execute(McpJsonRpcRequest request, boolean notification) {
        long startedAt = System.nanoTime();
        Future<HttpReply> future = executor.submit(() -> send(request));
        try {
            HttpReply reply = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            long durationMs = elapsedMillis(startedAt);
            LOGGER.info(
                    "MCP HTTP 请求完成 endpoint={} method={} requestId={} httpStatus={} durationMs={}",
                    endpoint, request.method(), request.id(), reply.status(), durationMs);
            if (reply.status() < 200 || reply.status() >= 300) {
                throw new McpTransportException("MCP HTTP 请求失败，HTTP 状态码 " + reply.status());
            }
            if (!notification && reply.body().isBlank()) {
                throw new McpTransportException("MCP HTTP 响应为空");
            }
            return reply;
        } catch (TimeoutException exception) {
            future.cancel(true);
            LOGGER.warn(
                    "MCP HTTP 请求超时 endpoint={} method={} requestId={} durationMs={}",
                    endpoint, request.method(), request.id(), elapsedMillis(startedAt));
            throw new McpTransportException("MCP HTTP 请求超时", exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpTransportException("MCP HTTP 请求等待被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            LOGGER.warn(
                    "MCP HTTP 请求异常 endpoint={} method={} requestId={} durationMs={}",
                    endpoint, request.method(), request.id(), elapsedMillis(startedAt), cause);
            throw new McpTransportException("MCP HTTP 请求执行失败", cause);
        }
    }

    private HttpReply send(McpJsonRpcRequest request) {
        return restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request.toJson(objectMapper))
                .exchange((ignoredRequest, response) -> new HttpReply(
                        response.getStatusCode().value(),
                        response.getHeaders().getContentType(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private boolean isJson(MediaType contentType) {
        return contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private record HttpReply(int status, MediaType contentType, String body) {
    }
}
