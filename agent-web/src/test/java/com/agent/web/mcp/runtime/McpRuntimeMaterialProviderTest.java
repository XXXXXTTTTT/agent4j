package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpSourceSnapshot;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRuntimeMaterialProviderTest {

    @Test
    void requiresVerifiedMaterialWithFixedEntryPoint() throws Exception {
        Path root = Files.createTempDirectory("agent4j-mcp-material-root");
        Path material = Files.createDirectory(root.resolve("snapshot"));
        Files.writeString(material.resolve("server.mjs"), "console.log('mcp');\n");
        McpRuntimeMaterialProvider.PreparedMaterial prepared = new McpRuntimeMaterialProvider.PreparedMaterial(
                material, McpRuntimeMaterialProvider.sha256(material), "node", List.of("server.mjs"));
        McpRuntimeMaterialProvider provider = new FileSystemMcpRuntimeMaterialProvider(root, snapshot -> prepared);

        assertThat(provider.requirePrepared(snapshot())).isEqualTo(prepared);
    }

    @Test
    void rejectsMaterialOutsideConfiguredRoot() throws Exception {
        Path root = Files.createTempDirectory("agent4j-mcp-material-root");
        Path outside = Files.createTempDirectory("agent4j-mcp-material-outside");
        Files.writeString(outside.resolve("server.mjs"), "console.log('mcp');\n");
        McpRuntimeMaterialProvider.PreparedMaterial prepared = new McpRuntimeMaterialProvider.PreparedMaterial(
                outside, McpRuntimeMaterialProvider.sha256(outside), "node", List.of("server.mjs"));
        McpRuntimeMaterialProvider provider = new FileSystemMcpRuntimeMaterialProvider(root, snapshot -> prepared);

        assertThatThrownBy(() -> provider.requirePrepared(snapshot()))
                .isInstanceOf(McpMaterialNotPreparedException.class)
                .hasMessage("MATERIAL_NOT_PREPARED");
    }

    @Test
    void rejectsAnySymbolicLinkInsidePreparedMaterialTree() throws Exception {
        Path root = Files.createTempDirectory("agent4j-mcp-material-root");
        Path material = Files.createDirectory(root.resolve("snapshot"));
        Files.writeString(material.resolve("server.mjs"), "console.log('mcp');\n");
        Path external = Files.createTempFile("agent4j-mcp-material-external", ".txt");
        try {
            Files.createSymbolicLink(material.resolve("linked.txt"), external);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前文件系统不支持符号链接测试");
        }
        McpRuntimeMaterialProvider.PreparedMaterial prepared = new McpRuntimeMaterialProvider.PreparedMaterial(
                material, "a".repeat(64), "server.mjs", List.of());
        McpRuntimeMaterialProvider provider = new FileSystemMcpRuntimeMaterialProvider(root, snapshot -> prepared);

        assertThatThrownBy(() -> McpRuntimeMaterialProvider.sha256(material))
                .isInstanceOf(McpMaterialNotPreparedException.class)
                .hasMessage("MATERIAL_NOT_PREPARED");
        assertThatThrownBy(() -> provider.requirePrepared(snapshot()))
                .isInstanceOf(McpMaterialNotPreparedException.class)
                .hasMessage("MATERIAL_NOT_PREPARED");
    }

    @Test
    void acceptsOnlyDeclaredEnvironmentNamesWithoutPersistingValues() {
        McpRuntimeSecretProvider provider = McpRuntimeSecretProvider.declaredNamesOnly();

        assertThat(provider.resolve(snapshot(), Map.of("MCP_TOKEN", "secret-value")))
                .containsExactlyEntriesOf(Map.of("MCP_TOKEN", "secret-value"));
        assertThatThrownBy(() -> provider.resolve(snapshot(), Map.of("OTHER_TOKEN", "secret-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环境变量未在 MCP 快照中声明");
    }

    private static McpSourceSnapshot snapshot() {
        return new McpSourceSnapshot(UUID.fromString("1faeec91-69a0-4bac-aaf6-c8c40d2fd3af"), "test-server",
                "src/test-server", URI.create("https://example.invalid/test-server"),
                "0123456789012345678901234567890123456789", Map.of("package.json", "abc"),
                "a".repeat(64), "1.0.0", "test", "MIT", "npx", List.of("-y", "test-server"),
                "test-server", List.of("MCP_TOKEN"), "test", Instant.parse("2026-08-13T00:00:00Z"));
    }
}
