package com.agent.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** 以本机真实工作区启动 Agent4J Docker Compose。 */
public final class ComposeLauncher {

    public static final URI DEFAULT_SERVER = URI.create("http://localhost:8080");

    private final ProcessStarter processStarter;
    private final HealthProbe healthProbe;
    private final Consumer<String> output;

    /** 创建使用真实 Docker CLI 和 HTTP readiness 探针的启动器。 */
    public ComposeLauncher(Consumer<String> output) {
        this(ComposeLauncher::startProcess, defaultHealthProbe(), output);
    }

    ComposeLauncher(
            ProcessStarter processStarter,
            HealthProbe healthProbe,
            Consumer<String> output) {
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter 不能为空");
        this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe 不能为空");
        this.output = Objects.requireNonNull(output, "output 不能为空");
    }

    /** 启动 Compose 并等待服务 readiness 端点返回成功。 */
    public void launch(Path workspace, Path composeFile, Duration healthTimeout) {
        Path realWorkspace = realDirectory(workspace, "workspace");
        Path realCompose = realFile(composeFile, "composeFile");
        Objects.requireNonNull(healthTimeout, "healthTimeout 不能为空");
        if (healthTimeout.isZero() || healthTimeout.isNegative()) {
            throw new IllegalArgumentException("healthTimeout 必须大于 0");
        }
        List<String> command = List.of(
                "docker",
                "compose",
                "-f",
                realCompose.toString(),
                "--env-file",
                ".env",
                "up",
                "-d",
                "--build");
        int exitCode = processStarter.start(
                command,
                realCompose.getParent(),
                Map.of("AGENT_CODE_HOST_WORKSPACE", realWorkspace.toString()));
        if (exitCode != 0) {
            throw new IllegalStateException("Docker Compose 启动失败，退出码: " + exitCode);
        }
        awaitHealthy(healthTimeout);
        output.accept("Agent4J 已启动: " + DEFAULT_SERVER);
    }

    private void awaitHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (healthProbe.isHealthy(DEFAULT_SERVER)) {
                return;
            }
            try {
                Thread.sleep(Duration.ofMillis(250));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Agent4J 健康状态被中断", exception);
            }
        }
        throw new IllegalStateException("Agent4J readiness 等待超时: " + DEFAULT_SERVER);
    }

    private static Path realDirectory(Path path, String name) {
        Path real = realPath(path, name);
        if (!Files.isDirectory(real)) {
            throw new IllegalArgumentException(name + " 必须是现有目录: " + path);
        }
        return real;
    }

    private static Path realFile(Path path, String name) {
        Path real = realPath(path, name);
        if (!Files.isRegularFile(real)) {
            throw new IllegalArgumentException(name + " 必须是现有文件: " + path);
        }
        return real;
    }

    private static Path realPath(Path path, String name) {
        Objects.requireNonNull(path, name + " 不能为空");
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException(name + " 不存在: " + path, exception);
        }
    }

    private static int startProcess(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .inheritIO();
        builder.environment().putAll(environment);
        try {
            return builder.start().waitFor();
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动 Docker Compose", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Docker Compose 被中断", exception);
        }
    }

    private static HealthProbe defaultHealthProbe() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        return server -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            server.toString() + "/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            try {
                return client.send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode() == 200;
            } catch (IOException exception) {
                return false;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Agent4J readiness 请求被中断", exception);
            }
        };
    }

    @FunctionalInterface
    interface ProcessStarter {
        int start(List<String> command, Path workingDirectory, Map<String, String> environment);
    }

    @FunctionalInterface
    interface HealthProbe {
        boolean isHealthy(URI server);
    }
}
