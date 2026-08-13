package com.agent.web.mcp.installation;

import com.agent.core.mcp.McpCatalogProvider;
import com.agent.core.mcp.McpCatalogSnapshot;
import com.agent.core.mcp.McpToolBindingSnapshot;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.agent.web.capability.InstallationScope;

/** 从同一主体和工作区的运行中安装重建 MCP Run 目录快照。 */
public final class InstalledMcpCatalogProvider implements McpCatalogProvider {
    private final McpInstallationRepository repository;

    public InstalledMcpCatalogProvider(McpInstallationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    @Override
    public McpCatalogSnapshot resolve(String actorUserId, UUID workspaceId) {
        Objects.requireNonNull(actorUserId, "actorUserId 不能为空");
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        List<McpInstallationAggregate> installations = repository.findRunningInstallations(actorUserId, workspaceId)
                .stream().filter(aggregate -> isVisibleRunning(aggregate, actorUserId, workspaceId)).toList();
        List<McpToolBindingSnapshot> bindings = installations.stream()
                .flatMap(aggregate -> aggregate.bindings().stream().map(binding -> new McpToolBindingSnapshot(
                        aggregate.installation().installationId(),
                        aggregate.installation().snapshotId(),
                        aggregate.installation().version(),
                        binding.localToolName(),
                        binding.remoteToolName(),
                        binding.riskLevel(),
                        binding.requiredCapabilities(),
                        binding.createdAt())))
                .sorted(Comparator.comparing(McpToolBindingSnapshot::localToolName))
                .toList();
        Instant updatedAt = installations.stream()
                .map(aggregate -> aggregate.installation().updatedAt())
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
        return new McpCatalogSnapshot(1, actorUserId, workspaceId, updatedAt, bindings, "");
    }

    private boolean isVisibleRunning(
            McpInstallationAggregate aggregate,
            String actorUserId,
            UUID workspaceId) {
        McpInstallationRecord installation = aggregate.installation();
        if (!actorUserId.equals(installation.actorUserId())
                || installation.status() != McpInstallationStatus.RUNNING) {
            return false;
        }
        return installation.scope() == InstallationScope.USER_GLOBAL
                || (installation.scope() == InstallationScope.WORKSPACE
                && workspaceId.equals(installation.workspaceId()));
    }
}
