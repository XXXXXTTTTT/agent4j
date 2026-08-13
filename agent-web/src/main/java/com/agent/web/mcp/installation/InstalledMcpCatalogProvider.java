package com.agent.web.mcp.installation;

import com.agent.core.mcp.McpCatalogProvider;
import com.agent.core.mcp.McpCatalogSnapshot;
import com.agent.core.mcp.McpToolBindingSnapshot;
import com.agent.core.tool.ToolRegistry;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.agent.web.capability.InstallationScope;

/** 从同一主体和工作区的运行中安装重建 MCP Run 目录快照。 */
public final class InstalledMcpCatalogProvider implements McpCatalogProvider {
    private final McpInstallationRepository repository;
    private final ToolRegistry toolRegistry;

    public InstalledMcpCatalogProvider(McpInstallationRepository repository, ToolRegistry toolRegistry) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
    }

    @Override
    public McpCatalogSnapshot resolve(String actorUserId, UUID workspaceId) {
        Objects.requireNonNull(actorUserId, "actorUserId 不能为空");
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        List<McpInstallationAggregate> installations = repository.findRunningInstallations(actorUserId, workspaceId)
                .stream().filter(aggregate -> isVisibleRunning(aggregate, actorUserId, workspaceId)).toList();
        List<McpToolBindingSnapshot> bindings = installations.stream()
                .flatMap(aggregate -> aggregate.bindings().stream()
                        .flatMap(binding -> bindingSnapshot(aggregate, binding).stream()))
                .sorted(Comparator.comparing(McpToolBindingSnapshot::localToolName))
                .toList();
        Instant updatedAt = installations.stream()
                .map(aggregate -> aggregate.installation().updatedAt())
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
        return new McpCatalogSnapshot(2, actorUserId, workspaceId, updatedAt, bindings, "");
    }

    /** 仅冻结当前注册表中真实存在的同一次运行时工具绑定。 */
    private Optional<McpToolBindingSnapshot> bindingSnapshot(
            McpInstallationAggregate aggregate,
            McpToolBindingRecord binding) {
        Optional<UUID> instanceId = toolRegistry.bindingInstanceId(binding.localToolName());
        var revision = toolRegistry.bindingRevision(binding.localToolName());
        if (instanceId.isEmpty() || revision.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new McpToolBindingSnapshot(
                aggregate.installation().installationId(),
                aggregate.installation().snapshotId(),
                aggregate.installation().version(),
                instanceId.get(),
                revision.getAsLong(),
                binding.localToolName(),
                binding.remoteToolName(),
                binding.riskLevel(),
                binding.requiredCapabilities(),
                binding.createdAt()));
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
        return workspaceId.equals(installation.runtimeWorkspaceId())
                && (installation.scope() == InstallationScope.USER_GLOBAL
                || installation.scope() == InstallationScope.WORKSPACE
                && workspaceId.equals(installation.workspaceId()));
    }
}
