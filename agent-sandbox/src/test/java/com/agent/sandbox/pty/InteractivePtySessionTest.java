package com.agent.sandbox.pty;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InteractivePtySessionTest {

    private static final Path BASH_EXECUTABLE = Path.of("D:/Git/bin/bash.exe");

    @Test
    void forwardsInputAndOutputAndClosesProcess() throws Exception {
        assumeTrue(Files.isRegularFile(BASH_EXECUTABLE), "需要 D:/Git/bin/bash.exe 执行 PTY 集成测试");
        List<String> output = new CopyOnWriteArrayList<>();
        AtomicReference<Integer> exitCode = new AtomicReference<>();
        InteractivePtySession session = InteractivePtySession.start(
                new PtyTarget(BASH_EXECUTABLE, Path.of("D:/Git")), output::add, exitCode::set);
        try {
            session.write("printf 'interactive-ok\\n'\n");
            session.resize(120, 32);
            waitUntil(() -> output.stream().anyMatch(text -> text.contains("interactive-ok")));
            assertThat(output).anyMatch(text -> text.contains("interactive-ok"));
        } finally {
            session.close();
        }
        assertThat(session.isClosed()).isTrue();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
