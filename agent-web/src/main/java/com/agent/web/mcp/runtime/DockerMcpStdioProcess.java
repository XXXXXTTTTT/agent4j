package com.agent.web.mcp.runtime;

import com.agent.core.tool.mcp.McpStdioProcess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Docker MCP stdio 三流及其生命周期状态。 */
public final class DockerMcpStdioProcess implements McpStdioProcess {
    private final InputStream stdout;
    private final OutputStream stdin;
    private final InputStream stderr;
    private final Runnable destroyAction;
    private final Runnable closeAttachedStreams;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private volatile Throwable failure;

    DockerMcpStdioProcess(
            InputStream stdout,
            OutputStream stdin,
            InputStream stderr,
            Runnable destroyAction,
            Runnable closeAttachedStreams) {
        this.stdout = Objects.requireNonNull(stdout, "stdout 不能为空");
        this.stdin = Objects.requireNonNull(stdin, "stdin 不能为空");
        this.stderr = Objects.requireNonNull(stderr, "stderr 不能为空");
        this.destroyAction = Objects.requireNonNull(destroyAction, "destroyAction 不能为空");
        this.closeAttachedStreams = Objects.requireNonNull(closeAttachedStreams, "closeAttachedStreams 不能为空");
    }

    public InputStream stdout() { return stdout; }
    public OutputStream stdin() { return stdin; }
    public InputStream stderr() { return stderr; }
    public boolean isAlive() { return alive.get(); }
    public Throwable failure() { return failure; }

    void fail(Throwable cause) {
        Throwable resolved = cause == null ? new IllegalStateException("MCP stdio 运行失败") : cause;
        if (alive.compareAndSet(true, false)) {
            failure = resolved;
            closeResources();
            try {
                destroyAction.run();
            } catch (Throwable cleanupFailure) {
                resolved.addSuppressed(cleanupFailure);
            }
        }
    }

    public void destroy() {
        if (!alive.compareAndSet(true, false)) {
            return;
        }
        try {
            destroyAction.run();
        } catch (Throwable cleanupFailure) {
            failure = cleanupFailure;
        }
        closeResources();
    }

    private void closeResources() {
        closeAttachedStreams.run();
        close(stdin);
        close(stdout);
        close(stderr);
    }

    private static void close(AutoCloseable resource) {
        try { resource.close(); } catch (Exception ignored) { }
    }
}
