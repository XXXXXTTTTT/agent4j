package com.agent.core.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpStdioTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsJsonLineAndRoutesConcurrentResponsesById() throws Exception {
        var process = new FakeProcess();
        try (var transport = new McpStdioTransport(process, mapper, Duration.ofSeconds(2), 4096)) {
            var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> transport.request(request("1")));
            var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> transport.request(request("2")));
            assertThat(process.awaitRequestLines(2)).isTrue();
            process.server.write(("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"value\":2}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"value\":1}}\n").getBytes());
            process.server.flush();
            assertThat(first.get()).extracting(McpJsonRpcResponse::id).isEqualTo("1");
            assertThat(second.get()).extracting(McpJsonRpcResponse::id).isEqualTo("2");
        }
    }

    @Test
    void ignoresServerNotificationAndSeparatesStderr() throws Exception {
        var process = new FakeProcess();
        try (var transport = new McpStdioTransport(process, mapper, Duration.ofSeconds(1), 4096)) {
            process.server.write(("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n").getBytes());
            process.server.flush();
            var responseFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> transport.request(request("1")));
            assertThat(process.awaitRequestLines(1)).isTrue();
            process.server.write(("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}\n").getBytes());
            process.server.flush();
            var response = responseFuture.get();
            process.error.write("warning\n".getBytes());
            process.error.flush();
            assertThat(response.id()).isEqualTo("1");
            assertThat(transport.stderr()).contains("warning");
        }
    }

    @Test
    void rejectsUnknownResponseIdAndOversizedFrame() throws Exception {
        var process = new FakeProcess();
        try (var transport = new McpStdioTransport(process, mapper, Duration.ofMillis(200), 32)) {
            var requestFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> transport.request(request("1")));
            assertThat(process.awaitRequestLines(1)).isTrue();
            process.server.write(("{\"jsonrpc\":\"2.0\",\"id\":\"unknown\",\"result\":{}}\n").getBytes());
            process.server.flush();
            assertThatThrownBy(requestFuture::get)
                    .hasRootCauseInstanceOf(McpTransportException.class);
        }
    }

    @Test
    void timesOutWhenServerDoesNotReply() throws Exception {
        var process = new FakeProcess();
        try (var transport = new McpStdioTransport(process, mapper, Duration.ofMillis(50), 4096)) {
            assertThatThrownBy(() -> transport.request(request("1")))
                    .isInstanceOf(McpTransportException.class)
                    .hasMessageContaining("超时");
        }
    }

    @Test
    void failsPendingRequestWhenStdoutCloses() throws Exception {
        var process = new FakeProcess();
        try (var transport = new McpStdioTransport(process, mapper, Duration.ofSeconds(1), 4096)) {
            var requestFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> transport.request(request("1")));
            assertThat(process.awaitRequestLines(1)).isTrue();
            process.server.close();
            assertThatThrownBy(requestFuture::get)
                    .hasRootCauseInstanceOf(McpTransportException.class)
                    .hasRootCauseMessage("MCP stdio 进程已退出");
        }
    }

    private McpJsonRpcRequest request(String id) {
        return McpJsonRpcRequest.request(id, "ping", mapper.createObjectNode());
    }

    private static final class FakeProcess implements McpStdioProcess {
        private final java.io.PipedInputStream input;
        private final java.io.PipedOutputStream server;
        private final ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
        private final Semaphore output = new Semaphore(0);
        private final InputStream stderr;
        private final java.io.PipedOutputStream error;

        private final OutputStream outputStream = new OutputStream() {
            @Override public void write(int b) { requestBytes.write(b); if (b == '\n') output.release(); }
            @Override public void write(byte[] b, int off, int len) { requestBytes.write(b, off, len); for (int i = off; i < off + len; i++) if (b[i] == '\n') output.release(); }
        };

        private FakeProcess() throws IOException {
            input = new java.io.PipedInputStream();
            server = new java.io.PipedOutputStream(input);
            java.io.PipedInputStream errorInput = new java.io.PipedInputStream();
            stderr = errorInput;
            error = new java.io.PipedOutputStream(errorInput);
        }

        private boolean awaitRequestLines(int expected) throws InterruptedException {
            return output.tryAcquire(expected, 2, TimeUnit.SECONDS);
        }

        @Override public InputStream stdout() { return input; }
        @Override public OutputStream stdin() { return outputStream; }
        @Override public InputStream stderr() { return stderr; }
        @Override public boolean isAlive() { return true; }
        @Override public void destroy() { try { input.close(); } catch (IOException ignored) { } }
    }
}
