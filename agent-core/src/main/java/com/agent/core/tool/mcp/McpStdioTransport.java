package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 使用 JSON Lines 在受治理 stdio 进程上承载 MCP JSON-RPC。 */
public final class McpStdioTransport implements McpTransport {

    private final McpStdioProcess process;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final int maxFrameBytes;
    private final BufferedWriter writer;
    private final Map<String, CompletableFuture<McpJsonRpcResponse>> pending = new ConcurrentHashMap<>();
    private final StringBuilder stderr = new StringBuilder();
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpStdioTransport(
            McpStdioProcess process,
            ObjectMapper objectMapper,
            Duration timeout,
            int maxFrameBytes) {
        this.process = Objects.requireNonNull(process, "process 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.timeout = requireTimeout(timeout);
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes 必须为正数");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.stdin(), StandardCharsets.UTF_8));
        readers.submit(() -> readStdout(process.stdout()));
        readers.submit(() -> readStderr(process.stderr()));
    }

    @Override
    public McpJsonRpcResponse request(McpJsonRpcRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        if (request.id() == null) {
            throw new IllegalArgumentException("stdio request 必须包含 id");
        }
        ensureOpen();
        CompletableFuture<McpJsonRpcResponse> future = new CompletableFuture<>();
        if (pending.putIfAbsent(request.id(), future) != null) {
            throw new IllegalArgumentException("重复的 MCP request id: " + request.id());
        }
        try {
            writeLine(objectMapper.writeValueAsString(request.toJson(objectMapper)));
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            pending.remove(request.id(), future);
            throw new McpTransportException("MCP stdio 请求超时", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            pending.remove(request.id(), future);
            throw new McpTransportException("MCP stdio 请求等待被中断", exception);
        } catch (ExecutionException exception) {
            throw asTransport(exception.getCause());
        } catch (IOException exception) {
            pending.remove(request.id(), future);
            throw new McpTransportException("MCP stdio 请求写入失败", exception);
        }
    }

    @Override
    public void notify(McpJsonRpcRequest notification) {
        Objects.requireNonNull(notification, "notification 不能为空");
        if (notification.id() != null) {
            throw new IllegalArgumentException("MCP notification 不得包含 id");
        }
        ensureOpen();
        try {
            writeLine(objectMapper.writeValueAsString(notification.toJson(objectMapper)));
        } catch (IOException exception) {
            throw new McpTransportException("MCP stdio 通知写入失败", exception);
        }
    }

    /** 返回已捕获的 stderr 文本，不含 stdout JSON-RPC 帧。 */
    public synchronized String stderr() {
        return stderr.toString();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        McpTransportException failure = new McpTransportException("MCP stdio transport 已关闭");
        pending.forEach((id, future) -> future.completeExceptionally(failure));
        pending.clear();
        try {
            writer.close();
        } catch (IOException exception) {
            failure.addSuppressed(exception);
        }
        process.destroy();
        readers.shutdownNow();
    }

    private void readStdout(InputStream input) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.getBytes(StandardCharsets.UTF_8).length > maxFrameBytes) {
                    failAll(new McpTransportException("MCP stdio 输出帧超过上限"));
                    return;
                }
                if (line.isBlank()) {
                    continue;
                }
                dispatch(line);
            }
            if (!closed.get()) {
                failAll(new McpTransportException("MCP stdio 进程已退出"));
            }
        } catch (IOException exception) {
            if (!closed.get()) {
                failAll(new McpTransportException("读取 MCP stdio stdout 失败", exception));
            }
        }
    }

    private void dispatch(String line) {
        try {
            var root = objectMapper.readTree(line);
            if (root == null || !root.isObject()) {
                throw new McpProtocolException("MCP stdio 帧必须是 JSON object");
            }
            if (!root.has("id")) {
                return;
            }
            if (!root.get("id").isTextual()) {
                throw new McpProtocolException("MCP stdio 响应 id 必须是字符串");
            }
            String id = root.get("id").textValue();
            CompletableFuture<McpJsonRpcResponse> future = pending.remove(id);
            if (future == null) {
                throw new McpProtocolException("MCP stdio 响应包含未知 id: " + id);
            }
            future.complete(McpJsonRpcResponse.parse(objectMapper, line, id));
        } catch (Exception exception) {
            failAll(exception instanceof McpTransportException transportException
                    ? transportException
                    : new McpTransportException("MCP stdio 响应解析失败", exception));
        }
    }

    private void readStderr(InputStream input) {
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int count;
            while (!closed.get() && (count = reader.read(buffer)) >= 0) {
                synchronized (this) {
                    stderr.append(buffer, 0, count);
                }
            }
        } catch (IOException ignored) {
            // stderr 仅用于诊断，读取失败不应伪造 JSON-RPC 响应。
        }
    }

    private synchronized void writeLine(String value) throws IOException {
        writer.write(value);
        writer.newLine();
        writer.flush();
    }

    private void failAll(Throwable failure) {
        pending.forEach((id, future) -> future.completeExceptionally(failure));
        pending.clear();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("MCP stdio transport 已关闭");
        }
        if (!process.isAlive()) {
            throw new McpTransportException("MCP stdio 进程已退出");
        }
    }

    private static McpTransportException asTransport(Throwable cause) {
        return cause instanceof McpTransportException transport
                ? transport
                : new McpTransportException("MCP stdio 请求失败", cause);
    }

    private static Duration requireTimeout(Duration value) {
        Objects.requireNonNull(value, "timeout 不能为空");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
        return value;
    }
}
