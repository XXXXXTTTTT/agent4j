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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
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
    private final long maxMaterialBytes;
    private final Duration preparationTimeout;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DockerMcpMaterialPreparationRunner(Path materialRoot, String nodeImage, String pythonPreparationImage,
                                              ObjectMapper objectMapper, Clock clock) {
        this(newDockerClient(), materialRoot, nodeImage, pythonPreparationImage, 268_435_456L,
                Duration.ofMinutes(10), objectMapper, clock);
    }

    public DockerMcpMaterialPreparationRunner(Path materialRoot, String nodeImage, String pythonPreparationImage,
                                              long maxMaterialBytes, Duration preparationTimeout,
                                              ObjectMapper objectMapper, Clock clock) {
        this(newDockerClient(), materialRoot, nodeImage, pythonPreparationImage, maxMaterialBytes,
                preparationTimeout, objectMapper, clock);
    }

    DockerMcpMaterialPreparationRunner(DockerClient docker, Path materialRoot, String nodeImage, String pythonPreparationImage,
                                       ObjectMapper objectMapper, Clock clock) {
        this(docker, materialRoot, nodeImage, pythonPreparationImage, 268_435_456L, Duration.ofMinutes(10), objectMapper, clock);
    }

    DockerMcpMaterialPreparationRunner(DockerClient docker, Path materialRoot, String nodeImage, String pythonPreparationImage,
                                       long maxMaterialBytes, Duration preparationTimeout, ObjectMapper objectMapper, Clock clock) {
        this.docker = Objects.requireNonNull(docker, "docker 不能为空");
        this.materialRoot = realDirectory(materialRoot);
        this.nodeImage = text(nodeImage, "nodeImage");
        this.pythonPreparationImage = pythonPreparationImage == null ? "" : pythonPreparationImage.trim();
        if (maxMaterialBytes <= 0) throw new IllegalArgumentException("maxMaterialBytes 必须为正数");
        this.maxMaterialBytes = maxMaterialBytes;
        if (preparationTimeout == null || preparationTimeout.isNegative() || preparationTimeout.isZero()) {
            throw new IllegalArgumentException("preparationTimeout 必须为正数");
        }
        this.preparationTimeout = preparationTimeout;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public McpPreparedMaterialRecord prepare(McpSourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        if ("uvx".equals(snapshot.command())) {
            if (pythonPreparationImage.isBlank()) throw new McpMaterialPreparationImageNotConfiguredException();
            return preparePython(snapshot);
        }
        return prepareNode(snapshot);
    }

    private McpPreparedMaterialRecord prepareNode(McpSourceSnapshot snapshot) {
        NodePackage nodePackage = nodePackage(snapshot);
        Path staging = createStaging(snapshot);
        String containerId = null;
        try {
            containerId = docker.createContainerCmd(nodeImage)
                    .withCmd("npm", "install", "--prefix", CONTAINER_DIRECTORY, "--omit=dev", "--ignore-scripts",
                            "--no-audit", "--no-fund", "--no-bin-links", nodePackage.coordinate())
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode("bridge").withReadonlyRootfs(true)
                            .withPrivileged(false).withBinds(new Bind(staging.toString(), new Volume(CONTAINER_DIRECTORY), AccessMode.rw))
                            .withTmpFs(Map.of("/tmp", "rw,nosuid,exec,size=512m")))
                    .withEnv("npm_config_cache=/tmp/npm-cache")
                    .exec().getId();
            docker.startContainerCmd(containerId).exec();
            waitForPreparation(containerId);
            rejectSymbolicLinks(staging);
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

    private McpPreparedMaterialRecord preparePython(McpSourceSnapshot snapshot) {
        PythonPackage pythonPackage = pythonPackage(snapshot);
        Path staging = createStaging(snapshot);
        String containerId = null;
        try {
            containerId = createPythonPreparationContainer(staging, pythonPackage.coordinate(), snapshot.launchBin());
            docker.startContainerCmd(containerId).exec();
            waitForPreparation(containerId);
            extractPythonMaterial(staging);
            Files.delete(staging.resolve("material.tar"));
            rejectSymbolicLinks(staging);
            String command = pythonMaterialCommand(staging, snapshot.launchBin());
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

    private String createPythonPreparationContainer(Path staging, String coordinate, String launchBin) {
        String script = """
                import pathlib
                import shutil
                import subprocess
                import sys
                temporary = pathlib.Path('/tmp/mcp-venv')
                destination = pathlib.Path('/tmp/mcp-material/venv')
                subprocess.run([sys.executable, '-m', 'venv', '--copies', str(temporary)], check=True)
                subprocess.run([str(temporary / 'bin' / 'python'), '-m', 'pip', 'install',
                                '--disable-pip-version-check', '--no-input', sys.argv[1]], check=True)
                shutil.copytree(temporary, destination, symlinks=False)
                for entry in (destination / 'bin').iterdir():
                    if not entry.is_file():
                        continue
                    try:
                        content = entry.read_text(encoding='utf-8')
                    except UnicodeDecodeError:
                        continue
                    rewritten = content.replace('/tmp/mcp-venv/', '/mcp-material/venv/')
                    if rewritten != content:
                        entry.write_text(rewritten, encoding='utf-8')
                entry = destination / 'bin' / sys.argv[2]
                content = entry.read_text(encoding='utf-8')
                if not content.startswith('#!/mcp-material/venv/bin/python\\n'):
                    raise RuntimeError('MATERIAL_PREPARATION_FAILED')
                subprocess.run(['tar', '--format=posix', '-cf', '/mcp-staging/material.tar',
                                '-C', '/tmp/mcp-material', '.'], check=True)
                """;
        return docker.createContainerCmd(pythonPreparationImage)
                .withCmd("python", "-c", script, coordinate, launchBin)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("bridge").withReadonlyRootfs(true)
                        .withPrivileged(false)
                        .withBinds(new Bind(staging.toString(), new Volume("/mcp-staging"), AccessMode.rw))
                        .withTmpFs(Map.of("/tmp", "rw,nosuid,exec,size=512m")))
                .exec().getId();
    }

    private void waitForPreparation(String containerId) {
        Integer exitCode = docker.waitContainerCmd(containerId).start()
                .awaitStatusCode(preparationTimeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        if (exitCode == null) throw new McpMaterialPreparationTimeoutException();
        if (exitCode != 0) throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
    }

    private void extractPythonMaterial(Path staging) throws IOException {
        Path archive = staging.resolve("material.tar");
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(archive)) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        try (var source = Files.newInputStream(archive); TarArchiveInputStream tar = new TarArchiveInputStream(source)) {
            long extracted = 0;
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                Path target = archiveTarget(staging, entry);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!entry.isFile()) throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
                Files.createDirectories(target.getParent());
                long remaining = maxMaterialBytes - extracted;
                if (remaining < entry.getSize()) throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
                try (var output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long entryBytes = 0;
                    while ((read = tar.read(buffer)) != -1) {
                        entryBytes += read;
                        if (entryBytes > entry.getSize() || extracted + entryBytes > maxMaterialBytes) {
                            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
                        }
                        output.write(buffer, 0, read);
                    }
                    if (entryBytes != entry.getSize()) throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
                    extracted += entryBytes;
                }
            }
        }
    }

    private static Path archiveTarget(Path staging, TarArchiveEntry entry) {
        String name = entry.getName();
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        Path relative = Path.of(name).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.getNameCount() == 0) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        Path target = staging.resolve(relative).normalize();
        if (!target.startsWith(staging)) throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        return target;
    }

    private void runPreparationContainer(String image, Path staging, List<String> command) {
        String containerId = null;
        try {
            containerId = docker.createContainerCmd(image)
                    .withCmd(command)
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode("bridge").withReadonlyRootfs(true)
                            .withPrivileged(false).withBinds(new Bind(staging.toString(), new Volume(CONTAINER_DIRECTORY), AccessMode.rw))
                            .withTmpFs(Map.of("/tmp", "rw,nosuid,exec,size=512m")))
                    .exec().getId();
            docker.startContainerCmd(containerId).exec();
            Integer exitCode = docker.waitContainerCmd(containerId).start().awaitStatusCode();
            if (exitCode == null || exitCode != 0) throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        } finally {
            if (containerId != null) removeContainer(containerId);
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

    private PythonPackage pythonPackage(McpSourceSnapshot snapshot) {
        if (snapshot.arguments().size() != 1) {
            throw new IllegalArgumentException("MCP Python 快照不是固定包==版本格式");
        }
        String coordinate = snapshot.arguments().getFirst();
        String suffix = "==" + snapshot.version();
        if (!coordinate.endsWith(suffix) || coordinate.length() == suffix.length()) {
            throw new IllegalArgumentException("MCP Python 快照版本与物料坐标不一致");
        }
        String name = coordinate.substring(0, coordinate.length() - suffix.length());
        if (!name.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("MCP Python 包名不合法");
        }
        return new PythonPackage(name, coordinate);
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

    private String pythonMaterialCommand(Path staging, String launchBin) throws IOException {
        if (launchBin == null || !launchBin.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        Path target = staging.resolve("venv").resolve("bin").resolve(launchBin).normalize();
        if (!target.startsWith(staging) || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        String command = staging.relativize(target).toString().replace('\\', '/');
        if (command.isBlank() || command.startsWith("/") || command.contains("..")) {
            throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
        }
        return command;
    }

    private void rejectSymbolicLinks(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            if (paths.anyMatch(Files::isSymbolicLink)) {
                throw new IllegalStateException("MATERIAL_PREPARATION_FAILED");
            }
        }
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

    private record PythonPackage(String name, String coordinate) { }
}
