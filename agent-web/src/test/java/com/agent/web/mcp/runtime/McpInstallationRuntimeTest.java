package com.agent.web.mcp.runtime;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.capability.InstallationScope;
import com.agent.web.identity.Actor;
import com.agent.web.mcp.installation.McpInstallationAggregate;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpPreparedMaterialRecord;
import com.agent.web.mcp.installation.McpRuntimeFailureCompletion;
import com.agent.web.mcp.installation.McpRuntimeStartCompletion;
import com.agent.web.mcp.installation.McpRuntimeStopCompletion;
import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.agent.web.mcp.installation.McpToolBindingRecord;
import com.agent.web.workspace.WorkspaceAccessService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

class McpInstallationRuntimeTest {
    private static final UUID WORKSPACE_ID = UUID.fromString("47e570a5-3ac4-4087-a590-f1714b8175dd");
    private static final UUID INSTALLATION_ID = UUID.fromString("9169db66-a555-44dd-a1e9-2ff4b1633ea6");
    private static final UUID SNAPSHOT_ID = UUID.fromString("8f68a4ec-d662-4c4a-9648-f3bea5eefbbe");

    @Test
    void rejectsStartWhenMaterialIsNotPreparedBeforeCreatingRunner() throws Exception {
        McpInstallationRepository repository = mock(McpInstallationRepository.class);
        McpInstallationAggregate aggregate = aggregate();
        when(repository.findInstallation(INSTALLATION_ID, "runtime-user", WORKSPACE_ID)).thenReturn(Optional.of(aggregate));
        when(repository.beginStart(eq(INSTALLATION_ID), eq("runtime-user"), eq(WORKSPACE_ID), eq(WORKSPACE_ID), eq(4L), any()))
                .thenReturn(installing());
        McpRuntimeMaterialProvider material = snapshot -> { throw new McpMaterialNotPreparedException(snapshot.snapshotId()); };
        DockerMcpStdioRunner runner = mock(DockerMcpStdioRunner.class);
        WorkspaceAccessService workspaces = mock(WorkspaceAccessService.class);
        Path workspace = Files.createTempDirectory("agent4j-mcp-runtime-workspace");
        com.agent.web.mcp.installation.McpPreparedMaterialRecord preparedMaterial = preparedMaterial();
        when(workspaces.requireWorkspace(eq(WORKSPACE_ID), eq("runtime-user"), any())).thenReturn(
                new com.agent.web.workspace.WorkspaceRecord(WORKSPACE_ID, "runtime-user", "runtime", workspace,
                        "repo", com.agent.web.workspace.WorkspacePermission.OWNER, Instant.EPOCH, Instant.EPOCH));
        McpInstallationRuntime runtime = new McpInstallationRuntime(() -> new Actor("runtime-user", "Runtime"),
                workspaces, repository, material, McpRuntimeSecretProvider.declaredNamesOnly(), runner,
                mock(ToolRegistry.class), new com.fasterxml.jackson.databind.ObjectMapper(), configuration(), java.time.Clock.systemUTC());

        assertThatThrownBy(() -> runtime.start(WORKSPACE_ID, INSTALLATION_ID,
                new McpInstallationRuntime.LifecycleRequest(4, WORKSPACE_ID, Map.of())))
                .isInstanceOf(McpMaterialNotPreparedException.class)
                .hasMessage("MATERIAL_NOT_PREPARED");
        verify(repository).completeFailure(any(McpRuntimeFailureCompletion.class));
    }

    @Test
    void requiresTargetWorkspaceForUserGlobalStart() {
        McpInstallationRepository repository = mock(McpInstallationRepository.class);
        McpInstallationRuntime runtime = new McpInstallationRuntime(() -> new Actor("runtime-user", "Runtime"),
                mock(WorkspaceAccessService.class), repository, snapshot -> { throw new AssertionError(); },
                McpRuntimeSecretProvider.declaredNamesOnly(), mock(DockerMcpStdioRunner.class), mock(ToolRegistry.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), configuration(), java.time.Clock.systemUTC());

        assertThatThrownBy(() -> runtime.start(WORKSPACE_ID, INSTALLATION_ID,
                new McpInstallationRuntime.LifecycleRequest(0, null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("targetWorkspaceId 不能为空");
    }

    @Test
    void cleansRegisteredToolsAndContainerWhenCompleteStartFails() throws Exception {
        McpInstallationRepository repository = mock(McpInstallationRepository.class);
        com.agent.web.mcp.installation.McpPreparedMaterialRecord preparedMaterial = preparedMaterial();
        McpInstallationAggregate aggregate = new McpInstallationAggregate(stopped(), snapshot(), preparedMaterial, List.of());
        when(repository.findInstallation(INSTALLATION_ID, "runtime-user", WORKSPACE_ID)).thenReturn(Optional.of(aggregate));
        when(repository.beginStart(eq(INSTALLATION_ID), eq("runtime-user"), eq(WORKSPACE_ID), eq(WORKSPACE_ID), eq(4L), any()))
                .thenReturn(installing());
        doThrow(new com.agent.web.mcp.installation.McpInstallationConflictException(INSTALLATION_ID, 5))
                .when(repository).completeStart(any(McpRuntimeStartCompletion.class));
        DockerMcpStdioRunner runner = mock(DockerMcpStdioRunner.class);
        java.io.PipedInputStream stdout = new java.io.PipedInputStream();
        java.io.PipedOutputStream responses = new java.io.PipedOutputStream(stdout);
        DockerMcpStdioProcess process = new DockerMcpStdioProcess(
                stdout, new RespondingOutputStream(responses),
                new java.io.ByteArrayInputStream(new byte[0]), "container-1", () -> { });
        when(runner.start(any(), any(), any(), any(), any())).thenReturn(process);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspaceAccessService workspaces = mock(WorkspaceAccessService.class);
        Path workspace = Files.createTempDirectory("agent4j-mcp-runtime-workspace");
        when(workspaces.requireWorkspace(eq(WORKSPACE_ID), eq("runtime-user"), any())).thenReturn(
                new com.agent.web.workspace.WorkspaceRecord(WORKSPACE_ID, "runtime-user", "runtime", workspace,
                        "repo", com.agent.web.workspace.WorkspacePermission.OWNER, Instant.EPOCH, Instant.EPOCH));
        McpInstallationRuntime runtime = new McpInstallationRuntime(() -> new Actor("runtime-user", "Runtime"), workspaces,
                repository, snapshot -> prepared(preparedMaterial),
                McpRuntimeSecretProvider.declaredNamesOnly(), runner, tools, new com.fasterxml.jackson.databind.ObjectMapper(),
                configuration(), java.time.Clock.systemUTC());

        assertThatThrownBy(() -> runtime.start(WORKSPACE_ID, INSTALLATION_ID,
                new McpInstallationRuntime.LifecycleRequest(4, WORKSPACE_ID, Map.of())))
                .isInstanceOf(com.agent.web.mcp.installation.McpInstallationConflictException.class);

        verify(tools).beginDrain(INSTALLATION_ID.toString());
        verify(tools).unregisterOwned(eq(INSTALLATION_ID.toString()), any());
        assertThat(process.isAlive()).isFalse();
        verify(repository).completeFailure(any(McpRuntimeFailureCompletion.class));
    }

    private static McpInstallationAggregate aggregate() {
        return new McpInstallationAggregate(stopped(), snapshot(), null, List.of());
    }

    private static McpInstallationRecord stopped() {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        return new McpInstallationRecord(INSTALLATION_ID, SNAPSHOT_ID, InstallationScope.WORKSPACE, WORKSPACE_ID,
                "runtime-user", McpInstallationStatus.STOPPED, "a".repeat(64), now, now, now,
                ToolRiskLevel.HIGH, Set.of(RequiredCapability.TOOL),
                com.agent.web.mcp.installation.WorkspaceMountMode.NONE,
                com.agent.web.mcp.installation.McpNetworkMode.NONE, "node:22-alpine", true, null, null, null, 4);
    }

    private static McpInstallationRecord installing() {
        McpInstallationRecord stopped = stopped();
        return new McpInstallationRecord(stopped.installationId(), stopped.snapshotId(), stopped.scope(), stopped.workspaceId(),
                stopped.actorUserId(), McpInstallationStatus.INSTALLING, stopped.confirmationTokenSha256(), stopped.createdAt(),
                stopped.confirmedAt(), stopped.updatedAt(), stopped.riskLevel(), stopped.requiredCapabilities(),
                stopped.workspaceMountMode(), stopped.networkMode(), stopped.runtimeImage(), true, null, null, null, 5);
    }

    private static McpSourceSnapshot snapshot() {
        return new McpSourceSnapshot(SNAPSHOT_ID, "runtime", "src/runtime", URI.create("https://example.invalid/runtime"),
                "0123456789012345678901234567890123456789", Map.of(), "b".repeat(64), "1.0.0", "runtime", "MIT",
                "npx", List.of("-y", "runtime"), "runtime", List.of("MCP_TOKEN"), "runtime", Instant.EPOCH);
    }

    private static com.agent.web.mcp.installation.McpPreparedMaterialRecord preparedMaterial() throws Exception {
        Path material = Files.createTempDirectory("agent4j-mcp-material");
        Files.writeString(material.resolve("runtime.mjs"), "");
        return new com.agent.web.mcp.installation.McpPreparedMaterialRecord(material,
                McpRuntimeMaterialProvider.sha256(material), "runtime.mjs", List.of(), Instant.EPOCH);
    }

    private static McpRuntimeMaterialProvider.PreparedMaterial prepared(
            com.agent.web.mcp.installation.McpPreparedMaterialRecord value) {
        return new McpRuntimeMaterialProvider.PreparedMaterial(value.directory(), value.sha256(), value.command(), value.arguments());
    }

    private static final class RespondingOutputStream extends java.io.OutputStream {
        private final java.io.OutputStream responses;
        private final StringBuilder request = new StringBuilder();
        private int responseCount;
        private RespondingOutputStream(java.io.OutputStream responses) { this.responses = responses; }
        @Override public void write(int value) throws java.io.IOException {
            if (value != '\n') { request.append((char) value); return; }
            com.fasterxml.jackson.databind.JsonNode message = new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.toString());
            if (!message.hasNonNull("id")) {
                request.setLength(0);
                return;
            }
            String id = message.path("id").asText();
            String result = responseCount++ == 0
                    ? "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}"
                    : "{\"tools\":[]}";
            responses.write(("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":" + result + "}\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            responses.flush();
            request.setLength(0);
        }
    }

    private static McpInstallationRuntime.McpRuntimeConfiguration configuration() {
        return new McpInstallationRuntime.McpRuntimeConfiguration("2025-06-18", "agent4j", "0.1.0",
                "/mcp-material", "", "", "/workspace",
                268435456, 500000000, 128, 1048576, 4194304, 1048576,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(30));
    }
}
