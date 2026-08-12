package com.agent.web.mcp.runtime;

import com.agent.core.tool.mcp.McpStdioProcess;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Docker MCP stdio 三流及单次失败抢占状态。 */
public final class DockerMcpStdioProcess implements McpStdioProcess {
    private final InputStream stdout;
    private final OutputStream stdin;
    private final InputStream stderr;
    private final String containerId;
    private final Runnable destroyAction;
    private final AtomicBoolean active = new AtomicBoolean(true);

    DockerMcpStdioProcess(
            InputStream stdout,
            OutputStream stdin,
            InputStream stderr,
            String containerId,
            Runnable destroyAction) {
        this.stdout = Objects.requireNonNull(stdout, "stdout 不能为空");
        this.stdin = Objects.requireNonNull(stdin, "stdin 不能为空");
        this.stderr = Objects.requireNonNull(stderr, "stderr 不能为空");
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalArgumentException("containerId 不能为空");
        }
        this.containerId = containerId;
        this.destroyAction = Objects.requireNonNull(destroyAction, "destroyAction 不能为空");
    }

    DockerMcpStdioProcess(InputStream stdout, OutputStream stdin, InputStream stderr, Runnable destroyAction) {
        this(stdout, stdin, stderr, "test-container", destroyAction);
    }

    @Override public InputStream stdout() { return stdout; }
    @Override public OutputStream stdin() { return stdin; }
    @Override public InputStream stderr() { return stderr; }
    public String containerId() { return containerId; }
    @Override public boolean isAlive() { return active.get(); }

    /** 原子抢占失败处理权；成功后调用方负责异步清理与通知。 */
    boolean claimFailure() {
        return active.compareAndSet(true, false);
    }

    @Override
    public void destroy() {
        if (active.compareAndSet(true, false)) {
            destroyAction.run();
        }
    }
}
