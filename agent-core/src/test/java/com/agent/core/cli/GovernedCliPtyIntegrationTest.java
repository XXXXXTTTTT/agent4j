package com.agent.core.cli;

import com.agent.sandbox.pty.PtyTarget;
import com.agent.sandbox.pty.SandboxTerminalService;
import com.agent.sandbox.pty.Stream;
import com.agent.sandbox.pty.TerminalLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GovernedCliPtyIntegrationTest {

    private static final Path BASH_EXECUTABLE = Path.of("D:/Git/bin/bash.exe");

    @TempDir
    Path workspace;

    @Test
    void executesGovernedCommandThroughRealGitBashPty() {
        assumeTrue(Files.isRegularFile(BASH_EXECUTABLE), "缺少精确 Git Bash 路径，跳过真实 PTY 集成测试");
        CliCommandCatalog catalog = new CliCommandCatalog(List.of(
                new CliCommandDefinition(
                        "print", "printf", List.of("%s\\n"), CliRiskLevel.READ_ONLY, Set.of())));
        PtyTarget target = new PtyTarget(BASH_EXECUTABLE, workspace);
        CliCommandIntent intent = new CliCommandIntent(
                "print", List.of("hello from governed cli"), workspace, target, Duration.ofSeconds(10));
        List<TerminalLog> logs = new CopyOnWriteArrayList<>();
        AtomicBoolean virtualReader = new AtomicBoolean();

        try (SandboxTerminalService terminal = new SandboxTerminalService()) {
            CliExecutionResult execution = new GovernedCliCommandExecutor(catalog, terminal)
                    .execute(
                            intent,
                            new CliAuthorizationContext(Set.of(), false, false),
                            log -> {
                                logs.add(log);
                                virtualReader.set(virtualReader.get() || Thread.currentThread().isVirtual());
                            })
                    .join();

            assertThat(execution.authorization().decision()).isEqualTo(CliAuthorizationDecision.ALLOWED);
            assertThat(execution.result()).get().satisfies(result -> {
                assertThat(result.exitCode()).isZero();
                assertThat(result.stdout()).contains("hello from governed cli");
                assertThat(result.timedOut()).isFalse();
            });
        }

        assertThat(logs).isNotEmpty();
        assertThat(logs).allMatch(log -> log.stream() == Stream.PTY);
        assertThat(virtualReader).isTrue();
    }
}
