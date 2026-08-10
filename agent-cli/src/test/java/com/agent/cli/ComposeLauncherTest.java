package com.agent.cli;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposeLauncherTest {

    @TempDir
    Path directory;

    @Test
    void validatesWorkspaceAndPassesOnlyExactComposeEnvironment() throws Exception {
        Path compose = Files.writeString(
                directory.resolve("docker-compose.local.yml"), "name: test\n");
        Path realDirectory = directory.toRealPath();
        Path realCompose = compose.toRealPath();
        List<String> command = new ArrayList<>();
        List<String> output = new ArrayList<>();
        ComposeLauncher launcher = new ComposeLauncher(
                (args, workingDirectory, environment) -> {
                    command.addAll(args);
                    assertThat(workingDirectory).isEqualTo(realCompose.getParent());
                    assertThat(environment).containsExactlyEntriesOf(Map.of(
                            "AGENT_CODE_HOST_WORKSPACE", realDirectory.toString()));
                    return 0;
                },
                ignored -> true,
                output::add);

        launcher.launch(directory, compose, Duration.ofSeconds(1));

        assertThat(command).containsExactly(
                "docker", "compose", "-f", realCompose.toString(),
                "--env-file", ".env", "up", "-d", "--build");
        assertThat(output).containsExactly("Agent4J 已启动: http://localhost:8080");
        assertThat(output).noneMatch(line -> line.contains(directory.toString()));
    }

    @Test
    void rejectsMissingWorkspaceOrComposeFile() throws IOException {
        ComposeLauncher launcher = new ComposeLauncher(
                (args, workingDirectory, environment) -> 0,
                ignored -> true,
                ignored -> { });
        Path compose = directory.resolve("docker-compose.local.yml");

        assertThatThrownBy(() -> launcher.launch(directory.resolve("missing"), compose,
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        Files.createDirectory(directory.resolve("workspace"));
        assertThatThrownBy(() -> launcher.launch(directory.resolve("workspace"), compose,
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
