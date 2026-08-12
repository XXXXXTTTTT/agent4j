package com.agent.web.mcp.runtime;

import com.agent.web.capability.CapabilityManagementAuditEvent;
import com.agent.web.identity.Actor;
import com.agent.web.identity.ActorResolver;
import com.agent.web.mcp.installation.McpInstallationAggregate;
import com.agent.web.mcp.installation.McpInstallationConflictException;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationRepository;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpPreparedMaterialRecord;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspacePermission;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 在安装生命周期外准备并原子登记离线 MCP 物料。 */
public final class McpMaterialPreparationService {
    private final ActorResolver actorResolver;
    private final WorkspaceAccessService workspaceAccess;
    private final McpInstallationRepository repository;
    private final McpMaterialPreparationRunner runner;
    private final Clock clock;

    public McpMaterialPreparationService(ActorResolver actorResolver, WorkspaceAccessService workspaceAccess,
                                         McpInstallationRepository repository, McpMaterialPreparationRunner runner,
                                         Clock clock) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.runner = Objects.requireNonNull(runner, "runner 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 仅允许 STOPPED 或 FAILED 的当前安装以调用方版本提交准备结果。 */
    public McpInstallationRecord prepare(UUID requestWorkspaceId, UUID installationId, long expectedVersion) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion 不能小于 0");
        Actor actor = actorResolver.current();
        workspaceAccess.requireWorkspace(requestWorkspaceId, actor.userId(), WorkspacePermission.OPERATOR);
        McpInstallationAggregate aggregate = repository.findInstallation(installationId, actor.userId(), requestWorkspaceId)
                .orElseThrow(() -> new McpInstallationConflictException(installationId, expectedVersion));
        McpInstallationRecord installation = aggregate.installation();
        if (installation.status() != McpInstallationStatus.STOPPED && installation.status() != McpInstallationStatus.FAILED) {
            throw new McpInstallationConflictException(installationId, expectedVersion);
        }
        if (installation.version() != expectedVersion) {
            throw new McpInstallationConflictException(installationId, expectedVersion);
        }
        McpPreparedMaterialRecord material;
        try {
            material = runner.prepare(aggregate.snapshot());
        } catch (RuntimeException exception) {
            repository.recordMaterialPreparationFailure(installationId, actor.userId(), requestWorkspaceId,
                    expectedVersion, audit(aggregate, requestWorkspaceId, "MCP_MATERIAL_PREPARATION_FAILED", "FAILED"));
            throw exception;
        }
        return repository.completeMaterialPreparation(installationId, actor.userId(), requestWorkspaceId,
                expectedVersion, material, audit(aggregate, requestWorkspaceId, "MCP_MATERIAL_PREPARED", "SUCCESS"));
    }

    private CapabilityManagementAuditEvent audit(McpInstallationAggregate aggregate, UUID workspaceId,
                                                  String eventType, String result) {
        Instant now = clock.instant();
        return new CapabilityManagementAuditEvent(eventType, aggregate.installation().actorUserId(), workspaceId,
                aggregate.installation().installationId(), null, null, aggregate.snapshot().commitSha(), result, now,
                UUID.randomUUID(), aggregate.installation().status().name(), aggregate.installation().status().name(), "");
    }
}
