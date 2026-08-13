package com.agent.web.mcp.installation;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.mcp.McpCatalogSnapshot;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.web.capability.InstallationScope;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstalledMcpCatalogProviderTest {

    private static final UUID WORKSPACE_A = UUID.fromString("5cb72f6c-4b8e-4d0c-93b4-c01245c3224b");
    private static final UUID WORKSPACE_B = UUID.fromString("1d875e70-d555-48b9-b72b-3eb181f08123");
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void resolvesOnlyCurrentActorAndActualRuntimeWorkspaceRunningBindings() {
        McpInstallationAggregate workspaceRunning = aggregate(
                "user-a", InstallationScope.WORKSPACE, WORKSPACE_A, McpInstallationStatus.RUNNING, "workspace.echo");
        McpInstallationAggregate globalRunning = aggregate(
                "user-a", InstallationScope.USER_GLOBAL, null, McpInstallationStatus.RUNNING, "global.echo");
        McpInstallationAggregate otherWorkspace = aggregate(
                "user-a", InstallationScope.WORKSPACE, WORKSPACE_B, McpInstallationStatus.RUNNING, "other-workspace.echo");
        McpInstallationAggregate otherActor = aggregate(
                "user-b", InstallationScope.USER_GLOBAL, null, McpInstallationStatus.RUNNING, "other-actor.echo");
        McpInstallationAggregate stopped = aggregate(
                "user-a", InstallationScope.WORKSPACE, WORKSPACE_A, McpInstallationStatus.STOPPED, "stopped.echo");
        RecordingRepository repository = new RecordingRepository(List.of(
                workspaceRunning, globalRunning, otherWorkspace, otherActor, stopped));

        try (DefaultToolRegistry registry = registryFor(
                workspaceRunning, globalRunning, otherWorkspace, otherActor, stopped)) {
            McpCatalogSnapshot snapshot = new InstalledMcpCatalogProvider(repository, registry)
                    .resolve("user-a", WORKSPACE_A);

            assertThat(repository.actorUserId).isEqualTo("user-a");
            assertThat(repository.workspaceId).isEqualTo(WORKSPACE_A);
            assertThat(snapshot.bindings()).extracting(binding -> binding.localToolName())
                    .containsExactly("mcp.global.echo", "mcp.workspace.echo");
        }
    }

    @Test
    void excludesUserGlobalBindingRunningInAnotherWorkspace() {
        McpInstallationAggregate globalRunningInWorkspaceA = aggregate(
                "user-a", InstallationScope.USER_GLOBAL, null, McpInstallationStatus.RUNNING, "global.echo");
        RecordingRepository repository = new RecordingRepository(List.of(globalRunningInWorkspaceA));

        try (DefaultToolRegistry registry = registryFor(globalRunningInWorkspaceA)) {
            McpCatalogSnapshot snapshot = new InstalledMcpCatalogProvider(repository, registry)
                    .resolve("user-a", WORKSPACE_B);

            assertThat(snapshot.bindings()).isEmpty();
        }
    }

    @Test
    void doesNotRequeryRepositoryAfterCatalogHasBeenFrozen() {
        McpInstallationAggregate running = aggregate(
                "user-a", InstallationScope.WORKSPACE, WORKSPACE_A, McpInstallationStatus.RUNNING, "workspace.echo");
        RecordingRepository repository = new RecordingRepository(List.of(running));
        try (DefaultToolRegistry registry = registryFor(running)) {
            InstalledMcpCatalogProvider provider = new InstalledMcpCatalogProvider(repository, registry);

            McpCatalogSnapshot frozen = provider.resolve("user-a", WORKSPACE_A);
            repository.installations = List.of();

            assertThat(frozen.bindings()).singleElement().extracting(binding -> binding.localToolName())
                    .isEqualTo("mcp.workspace.echo");
            assertThat(repository.calls).isEqualTo(1);
        }
    }

    private McpInstallationAggregate aggregate(
            String actorUserId,
            InstallationScope scope,
            UUID workspaceId,
            McpInstallationStatus status,
            String remoteToolName) {
        UUID installationId = UUID.nameUUIDFromBytes((actorUserId + scope + remoteToolName)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID snapshotId = UUID.nameUUIDFromBytes((remoteToolName + "-snapshot")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        McpInstallationRecord installation = new McpInstallationRecord(
                installationId, snapshotId, scope, workspaceId, actorUserId, status,
                "a".repeat(64), NOW, NOW, NOW, ToolRiskLevel.LOW,
                java.util.Set.of(RequiredCapability.TOOL), WorkspaceMountMode.NONE,
                McpNetworkMode.NONE, "node:22-alpine", true, WORKSPACE_A,
                "container", null, 3);
        McpSourceSnapshot source = new McpSourceSnapshot(snapshotId, remoteToolName, "src/" + remoteToolName,
                URI.create("https://example.test/" + remoteToolName), "b".repeat(40), Map.of(), "c".repeat(64),
                "1.0.0", "MCP", "MIT", "node", List.of(), "node", List.of(), "", NOW);
        McpToolBindingRecord binding = new McpToolBindingRecord(installationId, "mcp." + remoteToolName,
                remoteToolName, ToolRiskLevel.LOW, java.util.Set.of(RequiredCapability.TOOL), NOW);
        return new McpInstallationAggregate(installation, source, null, List.of(binding));
    }

    private DefaultToolRegistry registryFor(McpInstallationAggregate... aggregates) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        for (McpInstallationAggregate aggregate : aggregates) {
            registry.registerOwned(aggregate.installation().installationId().toString(), aggregate.bindings().stream()
                    .map(binding -> new ToolDefinition(binding.localToolName(), "测试 MCP 工具",
                            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type", "object"),
                            binding.requiredCapabilities(), binding.riskLevel(), Duration.ofSeconds(1),
                            (call, context) -> new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()))
                    .toList());
        }
        return registry;
    }

    private static final class RecordingRepository implements McpInstallationRepository {
        private List<McpInstallationAggregate> installations;
        private String actorUserId;
        private UUID workspaceId;
        private int calls;

        private RecordingRepository(List<McpInstallationAggregate> installations) {
            this.installations = installations;
        }

        @Override
        public List<McpInstallationAggregate> findRunningInstallations(String actorUserId, UUID workspaceId) {
            this.actorUserId = actorUserId;
            this.workspaceId = workspaceId;
            calls++;
            return installations;
        }

        @Override public McpInstallationRecord confirmInstallation(McpInstallationCommand command) { throw new UnsupportedOperationException(); }
        @Override public List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId) { throw new UnsupportedOperationException(); }
        @Override public McpInstallationRecord removeInstallation(UUID installationId, String actorUserId, UUID workspaceId, long expectedVersion, com.agent.web.capability.CapabilityManagementAuditEvent auditEvent) { throw new UnsupportedOperationException(); }
    }
}
