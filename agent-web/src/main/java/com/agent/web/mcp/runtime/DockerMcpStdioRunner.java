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
        try {
            PipedInputStream attachInput = new PipedInputStream();
            PipedOutputStream stdin = new PipedOutputStream(attachInput);
            PipedInputStream stdout = new PipedInputStream(spec.maxStdoutFrameBytes());
            PipedInputStream stderr = new PipedInputStream(spec.maxStderrBytes());
            PipedOutputStream stdoutWriter = new PipedOutputStream(stdout);
            PipedOutputStream stderrWriter = new PipedOutputStream(stderr);
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
            String id = response.getId();
            docker.startContainerCmd(id).exec();
            docker.attachContainerCmd(id).withFollowStream(true).withStdOut(true).withStdErr(true).withStdIn(attachInput)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        public void onNext(Frame frame) { try { if (frame.getStreamType() == StreamType.STDOUT) stdoutWriter.write(frame.getPayload()); else if (frame.getStreamType() == StreamType.STDERR) stderrWriter.write(frame.getPayload()); } catch (IOException ignored) { } }
                        public void onComplete() { DockerMcpStdioRunner.close(stdoutWriter); DockerMcpStdioRunner.close(stderrWriter); }
                        public void onError(Throwable error) { DockerMcpStdioRunner.close(stdoutWriter); DockerMcpStdioRunner.close(stderrWriter); }
                    });
            return new DockerMcpStdioProcess(stdout, stdin, stderr, () -> destroy(id));
        } catch (IOException exception) { throw new IllegalStateException("创建 MCP stdio 管道失败", exception); }
    }
    private static String[] command(McpDockerLaunchSpec spec) { List<String> command = new ArrayList<>(); command.add(spec.command()); command.addAll(spec.arguments()); return command.toArray(String[]::new); }
    private void destroy(String id) { try { docker.stopContainerCmd(id).withTimeout(0).exec(); } catch (RuntimeException ignored) { } try { docker.removeContainerCmd(id).withForce(true).exec(); } catch (RuntimeException ignored) { } }
    private static void close(AutoCloseable value) { try { value.close(); } catch (Exception ignored) { } }
    public void close() { try { docker.close(); } catch (IOException exception) { throw new IllegalStateException("关闭 Docker 客户端失败", exception); } }
}
