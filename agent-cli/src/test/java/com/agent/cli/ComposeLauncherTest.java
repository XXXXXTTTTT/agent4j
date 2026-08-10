package com.agent.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void probesExplicitLoopbackEndpointsUntilAgent4jIsReady() throws Exception {
        Path compose = Files.writeString(
                directory.resolve("docker-compose.local.yml"), "name: test\n");
        List<URI> probes = new ArrayList<>();
        ComposeLauncher launcher = new ComposeLauncher(
                (args, workingDirectory, environment) -> 0,
                server -> {
                    probes.add(server);
                    return URI.create("http://127.0.0.1:8080").equals(server);
                },
                ignored -> { });

        launcher.launch(directory, compose, Duration.ofSeconds(1));

        assertThat(probes).containsExactly(
                URI.create("http://[::1]:8080"),
                URI.create("http://127.0.0.1:8080"));
    }

    @Test
    void defaultHealthProbeRequiresUpStatusJson() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("<html>other service</html>");
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/actuator/health/readiness", exchange -> respond(
                exchange, body.get()));
        httpServer.start();
        try {
            URI server = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
            ComposeLauncher.HealthProbe probe = ComposeLauncher.defaultHealthProbe();

            assertThat(probe.isHealthy(server)).isFalse();
            body.set("");
            assertThat(probe.isHealthy(server)).isFalse();
            body.set("{\"status\":\"UP\"}");
            assertThat(probe.isHealthy(server)).isTrue();
        } finally {
            httpServer.stop(0);
        }
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
