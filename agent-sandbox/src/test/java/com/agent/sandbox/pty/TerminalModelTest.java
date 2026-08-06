package com.agent.sandbox.pty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerminalModelTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsStronglyTypedTerminalProtocol() throws IOException {
        Path bash = Files.createFile(temporaryDirectory.resolve("bash.exe"));
        DockerTarget dockerTarget = new DockerTarget("python:3.12-slim", temporaryDirectory, "/workspace");
        DockerTarget.ContainerWorkspaceSource containerSource =
                new DockerTarget.ContainerWorkspaceSource(
                        "agent4j-web-local", "/agent-workspace");
        DockerTarget containerTarget = new DockerTarget(
                "python:3.12-slim", temporaryDirectory, "/workspace", containerSource);
        PtyTarget ptyTarget = new PtyTarget(bash, temporaryDirectory);
        CommandRequest request = new CommandRequest(ptyTarget, "printf output", Duration.ofSeconds(2));
        CommandResult result = new CommandResult(7, "out", "err", false);
        TerminalLog log = new TerminalLog(Stream.PTY, "out");
        TerminalCommandExecutor executor = (command, consumer) ->
                CompletableFuture.completedFuture(result);

        assertThat(dockerTarget.hostWorkspace()).isEqualTo(temporaryDirectory.toAbsolutePath().normalize());
        assertThat(dockerTarget.workspaceSource())
                .isEqualTo(new DockerTarget.HostWorkspaceSource());
        assertThat(containerTarget.workspaceSource()).isSameAs(containerSource);
        assertThat(ptyTarget.bashExecutable()).isEqualTo(bash.toAbsolutePath().normalize());
        assertThat(request.target()).isSameAs(ptyTarget);
        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(log.stream()).isEqualTo(Stream.PTY);
        assertThat(Arrays.asList(Stream.values()))
                .containsExactly(Stream.STDOUT, Stream.STDERR, Stream.PTY);
        assertThat(executor.execute(request, ignored -> { }).join()).isEqualTo(result);
    }

    @Test
    void rejectsInvalidCommandRequest() throws IOException {
        Path bash = Files.createFile(temporaryDirectory.resolve("bash.exe"));
        PtyTarget target = new PtyTarget(bash, temporaryDirectory);

        assertThatThrownBy(() -> new CommandRequest(null, "printf output", Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommandRequest(target, " ", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandRequest(target, "printf output", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandRequest(target, "printf output", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidDockerTarget() {
        Path missing = temporaryDirectory.resolve("missing");

        assertThatThrownBy(() -> new DockerTarget(" ", temporaryDirectory, "/workspace"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockerTarget("python:3.12-slim", missing, "/workspace"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockerTarget("python:3.12-slim", temporaryDirectory, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockerTarget.ContainerWorkspaceSource(
                " ", "/agent-workspace"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockerTarget.ContainerWorkspaceSource(
                "agent4j-web", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockerTarget(
                "python:3.12-slim", temporaryDirectory, "/workspace", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsInvalidPtyTarget() throws IOException {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("directory"));
        Path file = Files.createFile(temporaryDirectory.resolve("file"));
        Path missing = temporaryDirectory.resolve("missing");

        assertThatThrownBy(() -> new PtyTarget(missing, temporaryDirectory))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PtyTarget(directory, temporaryDirectory))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PtyTarget(file, missing))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PtyTarget(file, file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidResultAndLog() {
        assertThatThrownBy(() -> new CommandResult(0, null, "", false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommandResult(0, "", null, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommandResult(0, "", "", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalLog(null, "text"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TerminalLog(Stream.STDOUT, null))
                .isInstanceOf(NullPointerException.class);
    }
}
