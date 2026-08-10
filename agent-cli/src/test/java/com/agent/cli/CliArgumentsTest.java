package com.agent.cli;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliArgumentsTest {

    @Test
    void parsesChatDefaultsToCurrentWorkspaceAndLocalServer() {
        CliArguments arguments = CliArguments.parse(new String[]{"chat"});

        assertThat(arguments.command()).isEqualTo(CliArguments.Command.CHAT);
        assertThat(arguments.workspace())
                .isEqualTo(Path.of(".").toAbsolutePath().normalize());
        assertThat(arguments.server()).isEqualTo(URI.create("http://localhost:8080"));
    }

    @Test
    void parsesServeDefaultComposeFileAndExplicitWorkspace() {
        CliArguments arguments = CliArguments.parse(new String[]{
                "serve", "--workspace", "D:/projects/demo"});

        assertThat(arguments.command()).isEqualTo(CliArguments.Command.SERVE);
        assertThat(arguments.workspace()).isEqualTo(Path.of("D:/projects/demo"));
        assertThat(arguments.composeFile()).isEqualTo(
                Path.of(".").toAbsolutePath().normalize()
                        .resolve("docker-compose.local.yml"));
    }

    @Test
    void parsesConversationListingServerOverride() {
        CliArguments arguments = CliArguments.parse(new String[]{
                "conversations", "--server", "http://127.0.0.1:9090/"});

        assertThat(arguments.command()).isEqualTo(CliArguments.Command.CONVERSATIONS);
        assertThat(arguments.server()).isEqualTo(URI.create("http://127.0.0.1:9090/"));
    }

    @Test
    void rejectsUnknownOptionAndMissingValue() {
        assertThatThrownBy(() -> CliArguments.parse(new String[]{"chat", "--unknown"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CliArguments.parse(new String[]{"chat", "--server"}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
