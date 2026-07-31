package com.agent.sandbox.docker;

import com.agent.sandbox.pty.CommandResult;
import com.agent.sandbox.pty.DockerTarget;
import com.agent.sandbox.pty.Stream;
import com.agent.sandbox.pty.TerminalLog;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DockerCommandExecutorTest {

    private static final String IMAGE = "python:3.12-slim";
    private static final String MANAGED_LABEL = "com.agent.runtime.managed";

    private static DockerClient verificationClient;

    @TempDir
    Path workspace;

    @BeforeAll
    static void verifyDockerEnvironment() {
        try {
            verificationClient = createDockerClient();
            verificationClient.pingCmd().exec();
            verificationClient.inspectImageCmd(IMAGE).exec();
        } catch (Exception exception) {
            assumeTrue(false,
                    "需要可用的 Docker Engine 与 python:3.12-slim 镜像: " + exception);
        }
    }

    @AfterEach
    void verifyContainerCleanup() {
        assertThat(verificationClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(MANAGED_LABEL, "true"))
                .exec())
                .isEmpty();
    }

    @AfterAll
    static void closeVerificationClient() throws IOException {
        if (verificationClient != null) {
            verificationClient.close();
        }
    }

    @Test
    void capturesSeparatedLogsAndWritesMountedWorkspace() throws IOException {
        List<TerminalLog> logs = new ArrayList<>();

        try (DockerCommandExecutor executor = new DockerCommandExecutor()) {
            CommandResult result = executor.execute(
                    target(),
                    "printf out; printf err >&2; printf changed > result.txt",
                    Duration.ofSeconds(10),
                    logs::add);

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).isEqualTo("out");
            assertThat(result.stderr()).isEqualTo("err");
            assertThat(result.timedOut()).isFalse();
            assertThat(logs).containsExactlyInAnyOrder(
                    new TerminalLog(Stream.STDOUT, "out"),
                    new TerminalLog(Stream.STDERR, "err"));
            assertThat(Files.readString(workspace.resolve("result.txt")))
                    .isEqualTo("changed");
        }
    }

    @Test
    void returnsActualNonZeroExitCode() {
        try (DockerCommandExecutor executor = new DockerCommandExecutor()) {
            CommandResult result = executor.execute(
                    target(),
                    "exit 9",
                    Duration.ofSeconds(10),
                    ignored -> { });

            assertThat(result.exitCode()).isEqualTo(9);
            assertThat(result.timedOut()).isFalse();
        }
    }

    @Test
    void stopsAndRemovesContainerAtTimeout() {
        long startedAt = System.nanoTime();

        try (DockerCommandExecutor executor = new DockerCommandExecutor()) {
            CommandResult result = executor.execute(
                    target(),
                    "sleep 2",
                    Duration.ofMillis(100),
                    ignored -> { });

            assertThat(result.exitCode()).isEqualTo(-1);
            assertThat(result.timedOut()).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofSeconds(2));
        }
    }

    private DockerTarget target() {
        return new DockerTarget(IMAGE, workspace, "/workspace");
    }

    private static DockerClient createDockerClient() {
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
