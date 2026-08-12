package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class DockerMcpMaterialPreparationRunnerTest {
    @Test
    void timesOutNodePreparationAndAlwaysRemovesContainerAndStaging() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        StartContainerCmd start = mock(StartContainerCmd.class);
        WaitContainerCmd wait = mock(WaitContainerCmd.class);
        WaitContainerResultCallback callback = mock(WaitContainerResultCallback.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId("preparation-container");
        when(docker.createContainerCmd("node:22-alpine")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        when(docker.startContainerCmd("preparation-container")).thenReturn(start);
        when(docker.waitContainerCmd("preparation-container")).thenReturn(wait);
        when(wait.start()).thenReturn(callback);
        when(callback.awaitStatusCode(eq(1L), eq(java.util.concurrent.TimeUnit.SECONDS))).thenReturn(null);
        when(docker.removeContainerCmd("preparation-container")).thenReturn(remove);
        when(docker.stopContainerCmd("preparation-container")).thenReturn(stop);
        Path root = Files.createTempDirectory("mcp-material-root");
        DockerMcpMaterialPreparationRunner runner = new DockerMcpMaterialPreparationRunner(docker, root,
                "node:22-alpine", "", 1024, java.time.Duration.ofSeconds(1), new ObjectMapper(), Clock.systemUTC());

        assertThatThrownBy(() -> runner.prepare(nodeSnapshot()))
                .isInstanceOf(McpMaterialPreparationTimeoutException.class)
                .hasMessage("MATERIAL_PREPARATION_TIMEOUT");

        verify(start).exec();
        InOrder cleanup = org.mockito.Mockito.inOrder(stop, remove);
        cleanup.verify(stop).withTimeout(0);
        cleanup.verify(stop).exec();
        cleanup.verify(remove).withForce(true);
        cleanup.verify(remove).exec();
        try (var paths = Files.list(root)) {
            assertThat(paths).isEmpty();
        }
    }
    @Test
    void rejectsPythonPreparationWithoutExplicitImage() throws Exception {
        DockerMcpMaterialPreparationRunner runner = new DockerMcpMaterialPreparationRunner(mock(DockerClient.class),
                Files.createTempDirectory("mcp-material-root"), "node:22-alpine", "", new ObjectMapper(), Clock.systemUTC());

        assertThatThrownBy(() -> runner.prepare(pythonSnapshot()))
                .isInstanceOf(McpMaterialPreparationImageNotConfiguredException.class)
                .hasMessage("MATERIAL_PREPARATION_IMAGE_NOT_CONFIGURED");
    }

    @Test
    void preparesFixedNodePackageIntoOfflineMaterialDirectory() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker Engine 不可用，跳过真实 Node MCP 物料准备测试");
        Path root = Files.createTempDirectory("mcp-material-root");
        McpSourceSnapshot snapshot = new McpSourceSnapshot(UUID.randomUUID(), "everything", "src/everything",
                URI.create("https://example.invalid/everything"), "0123456789012345678901234567890123456789", Map.of(),
                "b".repeat(64), "2026.7.4", "fixture", "MIT", "npx",
                List.of("-y", "@modelcontextprotocol/server-everything@2026.7.4"), "mcp-server-everything", List.of(), "fixture", Instant.EPOCH);
        try (DockerMcpMaterialPreparationRunner runner = new DockerMcpMaterialPreparationRunner(root, "node:22-alpine", "",
                new ObjectMapper(), Clock.systemUTC())) {
            var material = runner.prepare(snapshot);

            assertThat(material.directory()).startsWith(root.toRealPath());
            assertThat(material.command()).doesNotStartWith("/").doesNotContain("..");
            assertThat(material.arguments()).isEmpty();
            assertThat(Files.isRegularFile(material.directory().resolve(material.command()))).isTrue();
            assertThat(McpRuntimeMaterialProvider.sha256(material.directory())).isEqualTo(material.sha256());
        }
    }

    @Test
    void rejectsSymbolicLinkCreatedInsideMaterialTree() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker Engine 不可用，跳过 Linux 符号链接物料验证");
        Path root = Files.createTempDirectory("mcp-material-root");
        McpSourceSnapshot snapshot = nodeSnapshot();
        try (DockerMcpMaterialPreparationRunner runner = new DockerMcpMaterialPreparationRunner(root, "node:22-alpine", "",
                new ObjectMapper(), Clock.systemUTC())) {
            var material = runner.prepare(snapshot);
            try (var docker = dockerClient()) {
                String id = docker.createContainerCmd("alpine:3.20")
                        .withCmd("sh", "-c", "ln -s /etc/passwd /material/internal-link")
                        .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig().withBinds(
                                new com.github.dockerjava.api.model.Bind(material.directory().toString(),
                                        new com.github.dockerjava.api.model.Volume("/material"))))
                        .exec().getId();
                try {
                    docker.startContainerCmd(id).exec();
                    docker.waitContainerCmd(id).start().awaitStatusCode(30, java.util.concurrent.TimeUnit.SECONDS);
                } finally {
                    docker.removeContainerCmd(id).withForce(true).exec();
                }
            }
            Assumptions.assumeTrue(Files.isSymbolicLink(material.directory().resolve("internal-link")),
                    "Docker Desktop Windows bind 未保留 Linux 符号链接，无法在当前主机验证 Linux inode");
            assertThatThrownBy(() -> McpRuntimeMaterialProvider.sha256(material.directory()))
                    .isInstanceOf(McpMaterialNotPreparedException.class);
            McpRuntimeMaterialProvider provider = new FileSystemMcpRuntimeMaterialProvider(root, source ->
                    new McpRuntimeMaterialProvider.PreparedMaterial(material.directory(), material.sha256(), material.command(), material.arguments()));
            assertThatThrownBy(() -> provider.requirePrepared(snapshot)).isInstanceOf(McpMaterialNotPreparedException.class);
        }
    }

    private static McpSourceSnapshot nodeSnapshot() {
        return new McpSourceSnapshot(UUID.randomUUID(), "everything", "src/everything",
                URI.create("https://example.invalid/everything"), "0123456789012345678901234567890123456789", Map.of(),
                "b".repeat(64), "2026.7.4", "fixture", "MIT", "npx",
                List.of("-y", "@modelcontextprotocol/server-everything@2026.7.4"), "mcp-server-everything", List.of(), "fixture", Instant.EPOCH);
    }

    private static com.github.dockerjava.api.DockerClient dockerClient() {
        com.github.dockerjava.core.DefaultDockerClientConfig config = com.github.dockerjava.core.DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        com.github.dockerjava.transport.DockerHttpClient http = new com.github.dockerjava.httpclient5.ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
        return com.github.dockerjava.core.DockerClientImpl.getInstance(config, http);
    }

    @Test
    void preparesFixedPythonPackageIntoOfflineMaterialDirectoryWhenExplicitTestImageIsConfigured() throws Exception {
        String pythonImage = System.getenv("TEST_MCP_PYTHON_PREPARATION_IMAGE");
        Assumptions.assumeTrue(pythonImage != null && !pythonImage.isBlank(),
                "未设置 TEST_MCP_PYTHON_PREPARATION_IMAGE，跳过真实 Python MCP 物料准备测试");
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker Engine 不可用，跳过真实 Python MCP 物料准备测试");
        Path root = Files.createTempDirectory("mcp-python-material-root");
        McpSourceSnapshot snapshot = new McpSourceSnapshot(UUID.randomUUID(), "mcp-server-fetch", "src/fetch",
                URI.create("https://example.invalid/fetch"), "0123456789012345678901234567890123456789", Map.of(),
                "c".repeat(64), "2025.4.7", "fixture", "MIT", "uvx",
                List.of("mcp-server-fetch==2025.4.7"), "mcp-server-fetch", List.of(), "fixture", Instant.EPOCH);
        try (DockerMcpMaterialPreparationRunner runner = new DockerMcpMaterialPreparationRunner(root, "node:22-alpine",
                pythonImage, new ObjectMapper(), Clock.systemUTC())) {
            var material = runner.prepare(snapshot);

            assertThat(material.directory()).startsWith(root.toRealPath());
            assertThat(material.command()).isEqualTo("venv/bin/mcp-server-fetch");
            assertThat(material.arguments()).isEmpty();
            assertThat(Files.isRegularFile(material.directory().resolve(material.command()))).isTrue();
            assertThat(Files.isSymbolicLink(material.directory().resolve(material.command()))).isFalse();
            try (var paths = Files.walk(material.directory())) {
                assertThat(paths.noneMatch(Files::isSymbolicLink)).isTrue();
            }
            assertThat(Files.readString(material.directory().resolve(material.command())))
                    .startsWith("#!/mcp-material/venv/bin/python\n");
            assertThat(McpRuntimeMaterialProvider.sha256(material.directory())).isEqualTo(material.sha256());
        }
    }

    private static McpSourceSnapshot pythonSnapshot() {
        return new McpSourceSnapshot(UUID.randomUUID(), "python", "src/python", URI.create("https://example.invalid/python"),
                "0123456789012345678901234567890123456789", Map.of(), "a".repeat(64), "1.0.0", "python", "MIT",
                "uvx", List.of("python==1.0.0"), "python", List.of(), "python", Instant.EPOCH);
    }
}
