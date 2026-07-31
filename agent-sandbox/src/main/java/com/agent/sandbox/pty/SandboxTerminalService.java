package com.agent.sandbox.pty;

import com.agent.sandbox.docker.DockerCommandExecutor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 在虚拟线程中异步路由 Docker 与 PTY 命令。 */
public final class SandboxTerminalService implements TerminalCommandExecutor, AutoCloseable {

    private final ExecutorService executor;
    private final DockerCommandExecutor dockerExecutor;
    private final PtyCommandExecutor ptyExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 创建拥有独立 Docker 客户端与虚拟线程执行器的服务。 */
    public SandboxTerminalService() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.dockerExecutor = new DockerCommandExecutor();
        this.ptyExecutor = new PtyCommandExecutor();
    }

    /**
     * 异步执行命令，并按执行目标的精确类型路由。
     *
     * @param request     命令请求
     * @param logConsumer 实时日志接收器
     * @return 异步命令结果
     */
    @Override
    public CompletableFuture<CommandResult> execute(
            CommandRequest request,
            Consumer<TerminalLog> logConsumer) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(logConsumer, "logConsumer 不能为空");
        if (closed.get()) {
            throw new IllegalStateException("SandboxTerminalService 已关闭");
        }

        try {
            return CompletableFuture.supplyAsync(
                    () -> executeBlocking(request, logConsumer), executor);
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("SandboxTerminalService 已关闭", exception);
        }
    }

    private CommandResult executeBlocking(
            CommandRequest request,
            Consumer<TerminalLog> logConsumer) {
        try {
            return switch (request.target()) {
                case DockerTarget target -> dockerExecutor.execute(
                        target, request.bashCommand(), request.timeout(), logConsumer);
                case PtyTarget target -> ptyExecutor.execute(
                        target, request.bashCommand(), request.timeout(), logConsumer);
            };
        } catch (SandboxExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SandboxExecutionException("沙箱命令执行失败", exception);
        }
    }

    /** 停止接收新命令，并关闭服务持有的执行资源。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.close();
        } finally {
            dockerExecutor.close();
        }
    }
}
