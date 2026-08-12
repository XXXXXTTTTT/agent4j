package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
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

class DockerMcpMaterialPreparationRunnerTest {
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

    private static McpSourceSnapshot pythonSnapshot() {
        return new McpSourceSnapshot(UUID.randomUUID(), "python", "src/python", URI.create("https://example.invalid/python"),
                "0123456789012345678901234567890123456789", Map.of(), "a".repeat(64), "1.0.0", "python", "MIT",
                "uvx", List.of("python==1.0.0"), "python", List.of(), "python", Instant.EPOCH);
    }
}
