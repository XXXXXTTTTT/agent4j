package com.agent.web.mcp.runtime;

import com.agent.core.tool.mcp.McpStdioProcess;
import com.agent.web.mcp.installation.WorkspaceMountMode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 通过 Docker 容器运行持续 MCP stdio 服务。 */
public final class DockerMcpStdioRunner implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerMcpStdioRunner.class);
    private final DockerClient docker;
    private final ExecutorService cleanupExecutor;
    private final Set<DockerMcpStdioProcess> processes = ConcurrentHashMap.newKeySet();

    public DockerMcpStdioRunner() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
        this.docker = DockerClientImpl.getInstance(config, http);
        this.cleanupExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    DockerMcpStdioRunner(DockerClient docker, ExecutorService cleanupExecutor) {
        this.docker = Objects.requireNonNull(docker, "docker 不能为空");
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor 不能为空");
    }

    public McpStdioProcess start(
            McpDockerLaunchSpec spec,
            Map<String, String> environment,
            Path workspacePath,
            McpRuntimeFailureListener failureListener) {
        Objects.requireNonNull(spec, "spec 不能为空");
        Objects.requireNonNull(failureListener, "failureListener 不能为空");
        Map<String, String> values = Map.copyOf(environment == null ? Map.of() : environment);
        if (!spec.environmentVariableNames().containsAll(values.keySet())) {
            throw new IllegalArgumentException("环境变量不在启动规范白名单");
        }

        String containerId = null;
        PipedOutputStream stdin = null;
        BoundedPipe stdout = null;
        BoundedPipe stderr = null;
        try {
            PipedInputStream attachInput = new PipedInputStream();
            stdin = new PipedOutputStream(attachInput);
            stdout = new BoundedPipe(spec.maxStdoutBufferedBytes());
            stderr = new BoundedPipe(spec.maxStderrBytes());
            HostConfig hostConfig = hostConfig(spec, workspacePath);
            containerId = docker.createContainerCmd(spec.image())
                    .withCmd(command(spec)).withWorkingDir(spec.containerWorkingDirectory())
                    .withHostConfig(hostConfig).withLabels(labels(spec))
                    .withEnv(values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList())
                    .withAttachStdin(true).withAttachStdout(true).withAttachStderr(true)
                    .withStdinOpen(true).withTty(false).exec().getId();
            docker.startContainerCmd(containerId).exec();

            String id = containerId;
            AtomicReference<ResultCallback.Adapter<Frame>> callbackRef = new AtomicReference<>();
            AtomicReference<DockerMcpStdioProcess> processRef = new AtomicReference<>();
            final PipedOutputStream stdinRef = stdin;
            final BoundedPipe stdoutRef = stdout;
            final BoundedPipe stderrRef = stderr;
            DockerMcpStdioProcess process = new DockerMcpStdioProcess(
                    stdout.input(), stdin, stderr.input(),
                    () -> { cleanup(id, callbackRef.get(), stdinRef, stdoutRef, stderrRef); processes.removeIf(value -> value == processRef.get()); },
                    (reason, cause) -> cleanupExecutor.execute(() -> {
                        cleanup(id, callbackRef.get(), stdinRef, stdoutRef, stderrRef);
                        notifyFailure(failureListener, new McpRuntimeFailureListener.Event(
                                spec.installationId(), spec.snapshotId(), id, reason, cause));
                        processes.remove(processRef.get());
                    }));
            processRef.set(process);
            processes.add(process);

            ResultCallback.Adapter<Frame> callback = callback(spec, id, process, stdout, stderr);
            callbackRef.set(callback);
            docker.attachContainerCmd(id).withFollowStream(true).withStdOut(true).withStdErr(true)
                    .withStdIn(attachInput).exec(callback);
            return process;
        } catch (RuntimeException | IOException exception) {
            if (containerId != null) {
                cleanupSynchronously(containerId);
            }
            close(stdin);
            close(stdout);
            close(stderr);
            throw asRuntime(exception);
        }
    }

    private ResultCallback.Adapter<Frame> callback(
            McpDockerLaunchSpec spec, String containerId, DockerMcpStdioProcess process,
            BoundedPipe stdout, BoundedPipe stderr) {
        return new ResultCallback.Adapter<>() {
            private long stderrReceived;
            @Override public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                try {
                    if (frame.getStreamType() == StreamType.STDOUT) {
                        if (payload.length > spec.maxStdoutFrameBytes()) {
                            process.fail(McpRuntimeFailureListener.Reason.STDOUT_FRAME_LIMIT_EXCEEDED,
                                    new IOException("MCP stdout frame 超过上限"));
                        } else if (!stdout.offer(payload)) {
                            process.fail(McpRuntimeFailureListener.Reason.STDOUT_BUFFER_LIMIT_EXCEEDED,
                                    new IOException("MCP stdout 缓冲超过上限"));
                        }
                    } else if (frame.getStreamType() == StreamType.STDERR) {
                        stderrReceived += payload.length;
                        if (stderrReceived > spec.maxStderrBytes()) {
                            process.fail(McpRuntimeFailureListener.Reason.STDERR_LIMIT_EXCEEDED,
                                    new IOException("MCP stderr 超过上限"));
                        } else if (!stderr.offer(payload)) {
                            process.fail(McpRuntimeFailureListener.Reason.STREAM_IO_FAILED,
                                    new IOException("MCP stderr 管道不可写"));
                        }
                    }
                } catch (RuntimeException exception) {
                    process.fail(McpRuntimeFailureListener.Reason.STREAM_IO_FAILED, exception);
                }
            }
            @Override public void onComplete() {
                cleanupExecutor.execute(() -> process.fail(completionReason(containerId),
                        new IOException("MCP stdio attach 已结束")));
            }
            @Override public void onError(Throwable error) {
                process.fail(McpRuntimeFailureListener.Reason.ATTACH_DISCONNECTED, error);
            }
        };
    }

    private McpRuntimeFailureListener.Reason completionReason(String containerId) {
        try {
            boolean running = Boolean.TRUE.equals(docker.inspectContainerCmd(containerId).exec().getState().getRunning());
            return running ? McpRuntimeFailureListener.Reason.ATTACH_DISCONNECTED
                    : McpRuntimeFailureListener.Reason.CONTAINER_EXITED;
        } catch (RuntimeException exception) {
            return McpRuntimeFailureListener.Reason.CONTAINER_EXITED;
        }
    }

    private HostConfig hostConfig(McpDockerLaunchSpec spec, Path workspacePath) {
        HostConfig config = HostConfig.newHostConfig().withNetworkMode("none").withReadonlyRootfs(true)
                .withPrivileged(false).withMemory(spec.memoryBytes()).withNanoCPUs(spec.nanoCpus())
                .withPidsLimit(spec.pidsLimit());
        if (spec.workspaceMountMode() != WorkspaceMountMode.NONE) {
            Path source = Objects.requireNonNull(workspacePath, "workspacePath 不能为空");
            config.withBinds(new Bind(source.toString(), new Volume(spec.containerWorkingDirectory()),
                    spec.workspaceMountMode() == WorkspaceMountMode.READ_ONLY ? AccessMode.ro : AccessMode.rw));
        }
        return config;
    }

    private static Map<String, String> labels(McpDockerLaunchSpec spec) {
        return Map.of("com.agent.runtime.managed", "true", "com.agent.runtime.kind", "mcp",
                "com.agent.runtime.installation-id", spec.installationId().toString(),
                "com.agent.runtime.snapshot-id", spec.snapshotId().toString());
    }

    private static String[] command(McpDockerLaunchSpec spec) {
        ArrayList<String> command = new ArrayList<>();
        command.add(spec.command()); command.addAll(spec.arguments());
        return command.toArray(String[]::new);
    }

    private void cleanup(String id, AutoCloseable callback, AutoCloseable... streams) {
        close(callback);
        for (AutoCloseable stream : streams) close(stream);
        cleanupSynchronously(id);
    }

    private void cleanupSynchronously(String id) {
        RuntimeException failure = null;
        try { docker.stopContainerCmd(id).withTimeout(0).exec(); } catch (RuntimeException exception) { failure = exception; }
        try { docker.removeContainerCmd(id).withForce(true).exec(); } catch (RuntimeException exception) {
            if (failure != null) failure.addSuppressed(exception); else failure = exception;
        }
        if (failure != null) LOGGER.warn("MCP Docker 容器清理失败: containerId={}", id, failure);
    }

    private static void notifyFailure(McpRuntimeFailureListener listener, McpRuntimeFailureListener.Event event) {
        try { listener.onFailure(event); }
        catch (RuntimeException exception) { LOGGER.warn("MCP 运行失败监听器执行失败: reason={}", event.reason()); }
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception exception) { LOGGER.debug("关闭 MCP runtime 资源失败", exception); }
    }

    private static RuntimeException asRuntime(Exception exception) {
        return exception instanceof RuntimeException runtime ? runtime
                : new IllegalStateException("创建 MCP stdio 管道失败", exception);
    }

    @Override public void close() {
        for (DockerMcpStdioProcess process : processes) process.destroy();
        cleanupExecutor.shutdown();
        try { if (!cleanupExecutor.awaitTermination(30, TimeUnit.SECONDS)) cleanupExecutor.shutdownNow(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); cleanupExecutor.shutdownNow(); }
        try { docker.close(); } catch (IOException exception) { throw new IllegalStateException("关闭 Docker 客户端失败", exception); }
    }

    private static final class BoundedPipe implements AutoCloseable {
        private static final byte[] END = new byte[0];
        private final java.util.concurrent.BlockingQueue<byte[]> chunks = new java.util.concurrent.LinkedBlockingQueue<>();
        private final int limit;
        private int unread;
        private boolean closed;
        private BoundedPipe(int limit) { this.limit = limit; }
        synchronized boolean offer(byte[] payload) {
            if (closed || (long) unread + payload.length > limit) return false;
            unread += payload.length; chunks.offer(Arrays.copyOf(payload, payload.length)); return true;
        }
        InputStream input() { return new InputStream() {
            private byte[] current; private int offset;
            @Override public int read() throws IOException { byte[] one = new byte[1]; int read = read(one, 0, 1); return read < 0 ? -1 : one[0] & 0xff; }
            @Override public int read(byte[] bytes, int off, int len) throws IOException {
                if (len == 0) return 0;
                try {
                    synchronized (BoundedPipe.this) { if (closed) return -1; }
                    while (current == null || offset == current.length) { synchronized (BoundedPipe.this) { if (closed) return -1; } current = chunks.take(); offset = 0; if (current == END) return -1; }
                    int count = Math.min(len, current.length - offset); System.arraycopy(current, offset, bytes, off, count); offset += count;
                    synchronized (BoundedPipe.this) { unread -= count; } return count;
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IOException("读取 MCP 输出被中断", exception); }
            }
            @Override public void close() { BoundedPipe.this.close(); }
        }; }
        @Override public synchronized void close() { if (!closed) { closed = true; chunks.clear(); chunks.offer(END); } }
    }
}
