package com.agent.web.mcp.runtime;

import com.agent.core.tool.mcp.McpStdioProcess;
import com.agent.web.mcp.installation.WorkspaceMountMode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.LinkedBlockingQueue;

/** Docker MCP stdio 持续运行器。 */
public final class DockerMcpStdioRunner implements AutoCloseable {
    private final DockerClient docker;
    public DockerMcpStdioRunner() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
        docker = DockerClientImpl.getInstance(config, http);
    }
    public McpStdioProcess start(
            McpDockerLaunchSpec spec,
            Map<String, String> environment,
            Path workspacePath) {
        Objects.requireNonNull(spec, "spec 不能为空");
        Map<String, String> values = Map.copyOf(environment == null ? Map.of() : environment);
        if (!spec.environmentVariableNames().containsAll(values.keySet())) throw new IllegalArgumentException("环境变量不在启动规范白名单");
        String id = null;
        try {
            PipedInputStream attachInput = new PipedInputStream();
            PipedOutputStream stdin = new PipedOutputStream(attachInput);
            BoundedPipe stdoutPipe = new BoundedPipe(spec.maxStdoutFrameBytes());
            BoundedPipe stderrPipe = new BoundedPipe(spec.maxStderrBytes());
            InputStream stdout = stdoutPipe.input();
            InputStream stderr = stderrPipe.input();
            OutputStream stdoutWriter = stdoutPipe.output();
            OutputStream stderrWriter = stderrPipe.output();
            AtomicReference<DockerMcpStdioProcess> processRef = new AtomicReference<>();
            AtomicReference<ResultCallback.Adapter<Frame>> callbackRef = new AtomicReference<>();
            LimitedOutputStream boundedStdout = new LimitedOutputStream(stdoutWriter, spec.maxStdoutFrameBytes(),
                    () -> processRef.get().fail(new IllegalStateException("MCP stdout 超过上限")));
            LimitedOutputStream boundedStderr = new LimitedOutputStream(stderrWriter, spec.maxStderrBytes(),
                    () -> processRef.get().fail(new IllegalStateException("MCP stderr 超过上限")));
            HostConfig host = HostConfig.newHostConfig().withNetworkMode("none").withReadonlyRootfs(true).withPrivileged(false)
                    .withMemory(spec.memoryBytes()).withNanoCPUs(spec.nanoCpus()).withPidsLimit(spec.pidsLimit());
            if (spec.workspaceMountMode() != WorkspaceMountMode.NONE) {
                Path bindSource = Objects.requireNonNull(workspacePath, "workspacePath 不能为空");
                host.withBinds(new Bind(bindSource.toString(), new Volume(spec.containerWorkingDirectory()),
                        spec.workspaceMountMode() == WorkspaceMountMode.READ_ONLY ? AccessMode.ro : AccessMode.rw));
        }
            Map<String, String> labels = Map.of("com.agent.runtime.managed", "true", "com.agent.runtime.kind", "mcp",
                    "com.agent.runtime.installation-id", spec.installationId().toString(), "com.agent.runtime.snapshot-id", spec.snapshotId().toString());
            var response = docker.createContainerCmd(spec.image()).withCmd(command(spec)).withWorkingDir(spec.containerWorkingDirectory())
                    .withHostConfig(host).withLabels(labels).withEnv(values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList())
                    .withAttachStdin(true).withAttachStdout(true).withAttachStderr(true).withStdinOpen(true).withTty(false).exec();
            id = response.getId();
            docker.startContainerCmd(id).exec();
            final String containerId = id;
            DockerMcpStdioProcess process = new DockerMcpStdioProcess(stdout, stdin, stderr,
                    () -> destroy(containerId), () -> close(callbackRef.get()));
            processRef.set(process);
            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                public void onNext(Frame frame) {
                    try {
                        if (frame.getStreamType() == StreamType.STDOUT) boundedStdout.write(frame.getPayload());
                        else if (frame.getStreamType() == StreamType.STDERR) boundedStderr.write(frame.getPayload());
                    } catch (IOException exception) { process.fail(exception); }
                }
                public void onComplete() { process.fail(new IllegalStateException("MCP stdio attach 已结束")); }
                public void onError(Throwable error) { process.fail(error); }
            };
            callbackRef.set(callback);
            docker.attachContainerCmd(id).withFollowStream(true).withStdOut(true).withStdErr(true).withStdIn(attachInput).exec(callback);
            return process;
        } catch (IOException exception) { throw new IllegalStateException("创建 MCP stdio 管道失败", exception); }
        catch (RuntimeException exception) { if (id != null) destroy(id, exception); throw exception; }
    }
    private static String[] command(McpDockerLaunchSpec spec) { List<String> command = new ArrayList<>(); command.add(spec.command()); command.addAll(spec.arguments()); return command.toArray(String[]::new); }
    private void destroy(String id) { destroy(id, null); }
    private void destroy(String id, RuntimeException primary) {
        RuntimeException failure = primary;
        try { docker.stopContainerCmd(id).withTimeout(0).exec(); } catch (RuntimeException exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
        try { docker.removeContainerCmd(id).withForce(true).exec(); } catch (RuntimeException exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
        if (primary == null && failure != null) throw failure;
    }
    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream delegate; private final int limit; private final Runnable overflow; private int written;
        LimitedOutputStream(OutputStream delegate, int limit, Runnable overflow) { this.delegate=delegate; this.limit=limit; this.overflow=overflow; }
        public void write(int b) throws IOException { write(new byte[]{(byte)b},0,1); }
        public void write(byte[] bytes, int off, int len) throws IOException { if ((long)written + len > limit) { overflow.run(); throw new IOException("输出超过上限"); } delegate.write(bytes,off,len); written += len; }
        public void close() throws IOException { delegate.close(); }
    }
    private static final class BoundedPipe {
        private static final byte[] END = new byte[0];
        private final int limit;
        private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private int queued;
        private boolean closed;
        private BoundedPipe(int limit) { this.limit = limit; }
        private InputStream input() { return new InputStream() {
            private byte[] current; private int offset;
            public int read() throws IOException { byte[] one = new byte[1]; int count = read(one,0,1); return count < 0 ? -1 : one[0] & 0xff; }
            public int read(byte[] bytes, int off, int len) throws IOException {
                if (len == 0) return 0;
                try {
                    while (current == null || offset == current.length) { current = chunks.take(); offset = 0; if (current == END) return -1; synchronized (BoundedPipe.this) { queued -= current.length; } }
                    int count = Math.min(len, current.length - offset); System.arraycopy(current, offset, bytes, off, count); offset += count; return count;
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IOException("读取 MCP 输出被中断", exception); }
            }
            public void close() { synchronized (BoundedPipe.this) { closed = true; chunks.clear(); chunks.offer(END); } }
        }; }
        private OutputStream output() { return new OutputStream() {
            public void write(int b) throws IOException { write(new byte[]{(byte)b},0,1); }
            public void write(byte[] bytes, int off, int len) throws IOException { synchronized (BoundedPipe.this) { if (closed) throw new IOException("输出管道已关闭"); if ((long)queued + len > limit) throw new IOException("输出超过上限"); byte[] copy = Arrays.copyOfRange(bytes, off, off + len); queued += len; chunks.offer(copy); } }
            public void close() { synchronized (BoundedPipe.this) { if (!closed) { closed = true; chunks.offer(END); } } }
        }; }
    }
    private static void close(AutoCloseable value) { try { value.close(); } catch (Exception ignored) { } }
    public void close() { try { docker.close(); } catch (IOException exception) { throw new IllegalStateException("关闭 Docker 客户端失败", exception); } }
}
