package com.agent.sandbox.pty;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PtyCommandExecutorTest {

    private static final Path BASH_EXECUTABLE = Path.of("D:/Git/bin/bash.exe");

    @TempDir
    Path workingDirectory;

    private PtyCommandExecutor executor;
    private PtyTarget target;

    @BeforeEach
    void setUp() {
        assumeTrue(Files.isRegularFile(BASH_EXECUTABLE),
                "需要 D:/Git/bin/bash.exe 执行 PTY 集成测试");
        executor = new PtyCommandExecutor();
        target = new PtyTarget(BASH_EXECUTABLE, workingDirectory);
    }

    @Test
    void capturesOutputAndPublishesLogsOnVirtualThread() {
        List<TerminalLog> logs = new ArrayList<>();
        List<Boolean> virtualThreads = new ArrayList<>();

        CommandResult result = executor.execute(
                target,
                "printf 'output'",
                Duration.ofSeconds(5),
                log -> {
                    logs.add(log);
                    virtualThreads.add(Thread.currentThread().isVirtual());
                });

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("output");
        assertThat(result.stderr()).isEmpty();
        assertThat(result.timedOut()).isFalse();
        assertThat(logs).extracting(TerminalLog::stream)
                .containsOnly(Stream.PTY);
        assertThat(logs.stream()
                .map(TerminalLog::text)
                .collect(Collectors.joining()))
                .isEqualTo(result.stdout());
        assertThat(virtualThreads).containsOnly(true);
    }

    @Test
    void preservesAnsiControlSequences() {
        CommandResult result = executor.execute(
                target,
                "printf '\033[31mred\033[0m'",
                Duration.ofSeconds(5),
                ignored -> { });

        assertThat(result.stdout()).contains("\u001B[0;31mred\u001B[0m");
    }

    @Test
    void returnsNonZeroExitCodeWithoutException() {
        CommandResult result = executor.execute(
                target,
                "printf 'failure'; exit 7",
                Duration.ofSeconds(5),
                ignored -> { });

        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.stdout()).contains("failure");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void terminatesProcessAtTimeout() {
        long startedAt = System.nanoTime();

        CommandResult result = executor.execute(
                target,
                "sleep 2",
                Duration.ofMillis(100),
                ignored -> { });

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.stderr()).isEmpty();
        assertThat(result.timedOut()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void releasesWorkingDirectoryBeforeReturningFromTimeout() throws IOException {
        Path timeoutWorkingDirectory = Files.createDirectory(
                workingDirectory.resolve("timeout-workdir"));

        CommandResult result = executor.execute(
                new PtyTarget(BASH_EXECUTABLE, timeoutWorkingDirectory),
                "nohup sleep 5 >/dev/null 2>&1 & wait",
                Duration.ofMillis(100),
                ignored -> { });

        assertThat(result.timedOut()).isTrue();
        assertThat(Files.deleteIfExists(timeoutWorkingDirectory)).isTrue();
    }
}
