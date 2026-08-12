package com.agent.web.mcp.runtime;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolRiskLevel;
import com.agent.web.capability.InstallationScope;
import com.agent.web.identity.Actor;
import com.agent.web.mcp.installation.McpInstallationAggregate;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpPreparedMaterialRecord;
import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpMaterialPreparationServiceTest {
    private static final UUID WORKSPACE_ID = UUID.fromString("47e570a5-3ac4-4087-a590-f1714b8175dd");
    private static final UUID INSTALLATION_ID = UUID.fromString("9169db66-a555-44dd-a1e9-2ff4b1633ea6");
    private static final UUID SNAPSHOT_ID = UUID.fromString("8f68a4ec-d662-4c4a-9648-f3bea5eefbbe");

    @Test
    void preparesStoppedInstallationAndPersistsMaterialAtSuppliedVersion() throws Exception {
        McpInstallationRepository repository = mock(McpInstallationRepository.class);
        WorkspaceAccessService workspaces = mock(WorkspaceAccessService.class);
        McpInstallationAggregate aggregate = new McpInstallationAggregate(installation(4), snapshot(), null, List.of());
        McpPreparedMaterialRecord material = new McpPreparedMaterialRecord(Files.createTempDirectory("mcp-material"),
                "a".repeat(64), "node_modules/.bin/runtime", List.of(), Instant.parse("2026-08-13T00:00:00Z"));
        when(repository.findInstallation(INSTALLATION_ID, "runtime-user", WORKSPACE_ID)).thenReturn(Optional.of(aggregate));
        when(repository.completeMaterialPreparation(eq(INSTALLATION_ID), eq("runtime-user"), eq(WORKSPACE_ID), eq(4L),
                eq(material), any())).thenReturn(installation(5));
        McpMaterialPreparationService service = new McpMaterialPreparationService(
                () -> new Actor("runtime-user", "Runtime"), workspaces, repository, snapshot -> material, Clock.systemUTC());

        McpInstallationRecord result = service.prepare(WORKSPACE_ID, INSTALLATION_ID, 4);

        assertThat(result.version()).isEqualTo(5);
        verify(workspaces).requireWorkspace(WORKSPACE_ID, "runtime-user", WorkspacePermission.OPERATOR);
        verify(repository).completeMaterialPreparation(eq(INSTALLATION_ID), eq("runtime-user"), eq(WORKSPACE_ID), eq(4L),
                eq(material), any());
    }

    private static McpInstallationRecord installation(long version) {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        return new McpInstallationRecord(INSTALLATION_ID, SNAPSHOT_ID, InstallationScope.WORKSPACE, WORKSPACE_ID,
                "runtime-user", McpInstallationStatus.STOPPED, "b".repeat(64), now, now, now,
                ToolRiskLevel.HIGH, Set.of(RequiredCapability.TOOL),
                com.agent.web.mcp.installation.WorkspaceMountMode.NONE,
                com.agent.web.mcp.installation.McpNetworkMode.NONE, "node:22-alpine", true, null, null, null, version);
    }

    private static McpSourceSnapshot snapshot() {
        return new McpSourceSnapshot(SNAPSHOT_ID, "runtime", "src/runtime", URI.create("https://example.invalid/runtime"),
                "0123456789012345678901234567890123456789", Map.of(), "c".repeat(64), "1.0.0", "runtime", "MIT",
                "npx", List.of("-y", "runtime@1.0.0"), "runtime", List.of(), "runtime", Instant.EPOCH);
    }
}
