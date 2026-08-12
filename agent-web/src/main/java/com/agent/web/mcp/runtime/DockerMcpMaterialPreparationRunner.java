package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpPreparedMaterialRecord;
import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 使用无 Docker socket 的短生命周期容器准备 Node MCP 离线物料。 */
public final class DockerMcpMaterialPreparationRunner implements McpMaterialPreparationRunner, AutoCloseable {
    private static final String CONTAINER_DIRECTORY = "/mcp-material";
    private final DockerClient docker;
    private final Path materialRoot;
    private final String nodeImage;
    private final String pythonPreparationImage;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DockerMcpMaterialPreparationRunner(Path materialRoot, String nodeImage, String pythonPreparationImage,
                                              ObjectMapper objectMapper, Clock clock) {
        this(newDockerClient(), materialRoot, nodeImage, pythonPreparationImage, objectMapper, clock);
    }

    DockerMcpMaterialPreparationRunner(DockerClient docker, Path materialRoot, String nodeImage, String pythonPreparationImage,
                                       ObjectMapper objectMapper, Clock clock) {
        this.docker = Objects.requireNonNull(docker, "docker 不能为空");
        this.materialRoot = realDirectory(materialRoot);
        this.nodeImage = text(nodeImage, "nodeImage");
        this.pythonPreparationImage = pythonPreparationImage == null ? "" : pythonPreparationImage.trim();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public McpPreparedMaterialRecord prepare(McpSourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        if ("uvx".equals(snapshot.command())) {
            if (pythonPreparationImage.isBlank()) throw new McpMaterialPreparationImageNotConfiguredException();
            throw new UnsupportedOperationException("Python MCP 物料准备尚未实现");
        }
        NodePackage nodePackage = nodePackage(snapshot);
        Path staging = createStaging(snapshot);
        String containerId = null;
        try {
            containerId = docker.createContainerCmd(nodeImage)
                    .withCmd("npm", "install", "--prefix", CONTAINER_DIRECTORY, "--omit=dev", "--ignore-scripts",
                            "--no-audit", "--no-fund", nodePackage.coordinate())
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode("bridge").withReadonlyRootfs(true)
                            .withPrivileged(false).withBinds(new Bind(staging.toString(), new Volume(CONTAINER_DIRECTORY), AccessMode.rw))
                            .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=64m")))
                    .withEnv("npm_config_cache=/tmp/npm-cache")
                    .exec().getId();
            docker.startContainerCmd(containerId).exec();
            Integer exitCode = docker.waitContainerCmd(containerId).start().awaitStatusCode();
            if (exitCode == null || exitCode != 0) throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
            String command = materialCommand(staging, nodePackage, snapshot.launchBin());
            Path finalDirectory = materialRoot.resolve(snapshot.snapshotId().toString());
            if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) deleteDirectory(finalDirectory);
            Files.move(staging, finalDirectory, StandardCopyOption.ATOMIC_MOVE);
            String sha256 = McpRuntimeMaterialProvider.sha256(finalDirectory);
            return new McpPreparedMaterialRecord(finalDirectory, sha256, command, List.of(), clock.instant());
        } catch (IOException exception) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED", exception);
        } finally {
            if (containerId != null) removeContainer(containerId);
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteDirectory(staging);
        }
    }

    private NodePackage nodePackage(McpSourceSnapshot snapshot) {
        if (!"npx".equals(snapshot.command()) || snapshot.arguments().size() != 2
                || !"-y".equals(snapshot.arguments().getFirst())) {
            throw new IllegalArgumentException("MCP Node 快照不是固定 npx -y 包@版本格式");
        }
        String coordinate = snapshot.arguments().get(1);
        String suffix = "@" + snapshot.version();
        if (!coordinate.endsWith(suffix) || coordinate.length() == suffix.length()) {
            throw new IllegalArgumentException("MCP Node 快照版本与物料坐标不一致");
        }
        String name = coordinate.substring(0, coordinate.length() - suffix.length());
        if (!name.matches("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("MCP Node 包名不合法");
        }
        return new NodePackage(name, coordinate);
    }

    private String materialCommand(Path staging, NodePackage nodePackage, String launchBin) throws IOException {
        Path packageDirectory = staging.resolve("node_modules").resolve(nodePackage.name()).normalize();
        if (!packageDirectory.startsWith(staging) || !Files.isDirectory(packageDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        JsonNode metadata = objectMapper.readTree(Files.readString(packageDirectory.resolve("package.json")));
        JsonNode bin = metadata.path("bin");
        JsonNode entry = bin.isObject() ? bin.get(launchBin) : null;
        if (entry == null || !entry.isTextual() || entry.asText().isBlank()) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        Path entryPath = Path.of(entry.asText());
        Path target = packageDirectory.resolve(entryPath).normalize();
        if (entryPath.isAbsolute() || !target.startsWith(packageDirectory) || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        String command = staging.relativize(target).toString().replace('\\', '/');
        if (command.isBlank() || command.startsWith("/") || command.contains("..")) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        return command;
    }

    private Path createStaging(McpSourceSnapshot snapshot) {
        try { return Files.createTempDirectory(materialRoot, snapshot.snapshotId() + "-"); }
        catch (IOException exception) { throw new IllegalStateException("MATERIAL_PREPARATION_FAILED", exception); }
    }

    private void removeContainer(String containerId) {
        try { docker.removeContainerCmd(containerId).withForce(true).exec(); }
        catch (RuntimeException ignored) { }
    }

    private static void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static Path realDirectory(Path directory) {
        try { return Files.createDirectories(Objects.requireNonNull(directory, "materialRoot 不能为空")).toRealPath(); }
        catch (IOException exception) { throw new IllegalArgumentException("materialRoot 无法创建为目录", exception); }
    }

    private static DockerClient newDockerClient() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).build();
        return DockerClientImpl.getInstance(config, http);
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value;
    }

    @Override public void close() throws Exception { docker.close(); }

    private record NodePackage(String name, String coordinate) { }
}
