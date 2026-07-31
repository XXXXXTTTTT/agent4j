package com.agent.sandbox.docker;

import com.agent.sandbox.pty.CommandRequest;
import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.SandboxExecutionException;
import com.agent.sandbox.pty.Stream;
import com.agent.sandbox.pty.TerminalLog;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 使用 Docker-Java 在一次性容器中执行 Bash 命令。 */
public final class DockerCommandExecutor implements AutoCloseable {

    private static final Map<String, String> MANAGED_LABEL =
            Map.of("com.agent.runtime.managed", "true");

    private final DockerClient dockerClient;

    /** 使用当前 Docker 环境创建执行器。 */
    public DockerCommandExecutor() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * 在绑定工作区的一次性容器中执行 Bash 命令。
     *
     * @param target      Docker 执行目标
     * @param bashCommand Bash 命令
     * @param timeout     超时时间
     * @param logConsumer 实时日志接收器
     * @return 命令结果
     */
    public CommandResult execute(
            DockerTarget target,
            String bashCommand,
            Duration timeout,
            Consumer<TerminalLog> logConsumer) {
        CommandRequest request = new CommandRequest(target, bashCommand, timeout);
        Objects.requireNonNull(logConsumer, "logConsumer 不能为空");

        String containerId = null;
        RuntimeException primaryFailure = null;
        try {
            HostConfig hostConfig = HostConfig.newHostConfig().withBinds(new Bind(
                    target.hostWorkspace().toString(),
                    new Volume(target.containerWorkspace()),
                    AccessMode.rw));
            CreateContainerResponse container = dockerClient
                    .createContainerCmd(target.image())
                    .withCmd("bash", "-lc", request.bashCommand())
                    .withWorkingDir(target.containerWorkspace())
                    .withHostConfig(hostConfig)
                    .withLabels(MANAGED_LABEL)
                    .exec();
            containerId = container.getId();
            return runContainer(containerId, request.timeout(), logConsumer);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            primaryFailure = new SandboxExecutionException("Docker 命令等待被中断", exception);
            throw primaryFailure;
        } catch (SandboxExecutionException exception) {
            primaryFailure = exception;
            throw exception;
        } catch (Exception exception) {
            primaryFailure = new SandboxExecutionException("Docker 命令执行失败", exception);
            throw primaryFailure;
        } finally {
            if (containerId != null) {
                removeContainer(containerId, primaryFailure);
            }
        }
    }

    private CommandResult runContainer(
            String containerId,
            Duration timeout,
            Consumer<TerminalLog> logConsumer) throws InterruptedException {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        AtomicReference<RuntimeException> callbackFailure = new AtomicReference<>();

        try (ResultCallback.Adapter<Frame> logCallback = createLogCallback(
                stdout, stderr, logConsumer, callbackFailure);
                WaitContainerResultCallback waitCallback = new WaitContainerResultCallback()) {
            dockerClient.startContainerCmd(containerId).exec();
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .exec(logCallback);
            dockerClient.waitContainerCmd(containerId).exec(waitCallback);

            boolean finished = waitCallback.awaitCompletion(
                    timeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean timedOut = !finished;
            Integer exitCode = null;
            if (timedOut) {
                dockerClient.stopContainerCmd(containerId)
                        .withTimeout(0)
                        .exec();
            } else {
                exitCode = waitCallback.awaitStatusCode();
            }
            logCallback.awaitCompletion();

            RuntimeException logFailure = callbackFailure.get();
            if (logFailure != null) {
                throw new SandboxExecutionException("Docker 日志接收失败", logFailure);
            }
            return new CommandResult(
                    timedOut ? -1 : exitCode,
                    stdout.toString(),
                    stderr.toString(),
                    timedOut);
        } catch (IOException exception) {
            throw new SandboxExecutionException("关闭 Docker 日志回调失败", exception);
        }
    }

    private ResultCallback.Adapter<Frame> createLogCallback(
            StringBuilder stdout,
            StringBuilder stderr,
            Consumer<TerminalLog> logConsumer,
            AtomicReference<RuntimeException> callbackFailure) {
        return new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (callbackFailure.get() != null) {
                    return;
                }
                String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
                try {
                    if (frame.getStreamType() == StreamType.STDOUT) {
                        stdout.append(text);
                        logConsumer.accept(new TerminalLog(Stream.STDOUT, text));
                    } else if (frame.getStreamType() == StreamType.STDERR) {
                        stderr.append(text);
                        logConsumer.accept(new TerminalLog(Stream.STDERR, text));
                    } else {
                        callbackFailure.compareAndSet(null, new SandboxExecutionException(
                                "不支持的 Docker 日志流: " + frame.getStreamType()));
                    }
                } catch (RuntimeException exception) {
                    callbackFailure.compareAndSet(null, exception);
                }
            }
        };
    }

    private void removeContainer(String containerId, RuntimeException primaryFailure) {
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .exec();
        } catch (RuntimeException cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
                return;
            }
            throw new SandboxExecutionException("删除 Docker 容器失败", cleanupFailure);
        }
    }

    /** 关闭 Docker 客户端。 */
    @Override
    public void close() {
        try {
            dockerClient.close();
        } catch (IOException exception) {
            throw new SandboxExecutionException("关闭 Docker 客户端失败", exception);
        }
    }
}
