package com.agent.sandbox.pty;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SandboxTerminalServiceTest {

    private static final Path BASH_EXECUTABLE = Path.of("D:/Git/bin/bash.exe");
    private static final String IMAGE = "python:3.12-slim";

    @TempDir
    Path workspace;

    @Test
    void returnsIncompleteFutureAndPublishesPtyLogsFromVirtualThread() {
        assumeGitBashExists();
        List<Boolean> virtualThreads = new ArrayList<>();

        try (SandboxTerminalService service = new SandboxTerminalService()) {
            CompletableFuture<CommandResult> future = service.execute(
                    new CommandRequest(
                            new PtyTarget(BASH_EXECUTABLE, workspace),
                            "sleep 1; printf finished",
                            Duration.ofSeconds(5)),
                    log -> virtualThreads.add(Thread.currentThread().isVirtual()));

            assertThat(future).isNotCompleted();
            assertThat(future.join().stdout()).contains("finished");
            assertThat(virtualThreads).isNotEmpty().containsOnly(true);
        }
    }

    @Test
    void routesDockerTargetByItsExactType() {
        assumeDockerEnvironment();

        try (SandboxTerminalService service = new SandboxTerminalService()) {
            CommandResult result = service.execute(
                    new CommandRequest(
                            new DockerTarget(IMAGE, workspace, "/workspace"),
                            "printf docker",
                            Duration.ofSeconds(10)),
                    ignored -> { })
                    .join();

            assertThat(result.stdout()).isEqualTo("docker");
            assertThat(result.stderr()).isEmpty();
        }
    }

    @Test
    void rejectsNewCommandsAfterClose() {
        assumeGitBashExists();
        SandboxTerminalService service = new SandboxTerminalService();
        CommandRequest request = new CommandRequest(
                new PtyTarget(BASH_EXECUTABLE, workspace),
                "printf output",
                Duration.ofSeconds(5));

        service.close();

        assertThatThrownBy(() -> service.execute(request, ignored -> { }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preservesLogConsumerFailureCause() {
        assumeGitBashExists();
        IllegalStateException consumerFailure = new IllegalStateException("consumer failure");

        try (SandboxTerminalService service = new SandboxTerminalService()) {
            CompletableFuture<CommandResult> future = service.execute(
                    new CommandRequest(
                            new PtyTarget(BASH_EXECUTABLE, workspace),
                            "printf output",
                            Duration.ofSeconds(5)),
                    ignored -> {
                        throw consumerFailure;
                    });

            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .satisfies(exception -> {
                        Throwable serviceFailure = exception.getCause();
                        assertThat(serviceFailure)
                                .isInstanceOf(SandboxExecutionException.class)
                                .hasCause(consumerFailure);
                    });
        }
    }

    @Test
    void validatesRequestAndLogConsumerSynchronously() {
        try (SandboxTerminalService service = new SandboxTerminalService()) {
            assertThatThrownBy(() -> service.execute(null, ignored -> { }))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> service.execute(
                    new CommandRequest(
                            new DockerTarget(IMAGE, workspace, "/workspace"),
                            "printf output",
                            Duration.ofSeconds(5)),
                    null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    private void assumeGitBashExists() {
        assumeTrue(Files.isRegularFile(BASH_EXECUTABLE),
                "需要 D:/Git/bin/bash.exe 执行 PTY 集成测试");
    }

    private void assumeDockerEnvironment() {
        try (DockerClient client = createDockerClient()) {
            client.pingCmd().exec();
            client.inspectImageCmd(IMAGE).exec();
        } catch (IOException | RuntimeException exception) {
            assumeTrue(false,
                    "需要可用的 Docker Engine 与 python:3.12-slim 镜像: " + exception);
        }
    }

    private DockerClient createDockerClient() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
