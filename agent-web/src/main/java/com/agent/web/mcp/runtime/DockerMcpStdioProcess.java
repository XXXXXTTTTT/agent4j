package com.agent.web.mcp.runtime;

import com.agent.core.tool.mcp.McpStdioProcess;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Docker MCP stdio 三流及单次失败抢占状态。 */
public final class DockerMcpStdioProcess implements McpStdioProcess {
    private final InputStream stdout;
    private final OutputStream stdin;
    private final InputStream stderr;
    private final Runnable destroyAction;
    private final BiConsumer<McpRuntimeFailureListener.Reason, Throwable> failureAction;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean failureClaimed = new AtomicBoolean();

    DockerMcpStdioProcess(
            InputStream stdout,
            OutputStream stdin,
            InputStream stderr,
            Runnable destroyAction,
            BiConsumer<McpRuntimeFailureListener.Reason, Throwable> failureAction) {
        this.stdout = Objects.requireNonNull(stdout, "stdout 不能为空");
        this.stdin = Objects.requireNonNull(stdin, "stdin 不能为空");
        this.stderr = Objects.requireNonNull(stderr, "stderr 不能为空");
        this.destroyAction = Objects.requireNonNull(destroyAction, "destroyAction 不能为空");
        this.failureAction = Objects.requireNonNull(failureAction, "failureAction 不能为空");
    }

    @Override public InputStream stdout() { return stdout; }
    @Override public OutputStream stdin() { return stdin; }
    @Override public InputStream stderr() { return stderr; }
    @Override public boolean isAlive() { return active.get(); }

    void fail(McpRuntimeFailureListener.Reason reason, Throwable cause) {
        if (failureClaimed.compareAndSet(false, true) && active.compareAndSet(true, false)) {
            failureAction.accept(reason, cause);
        }
    }

    @Override
    public void destroy() {
        if (active.compareAndSet(true, false)) {
            destroyAction.run();
        }
    }
}
