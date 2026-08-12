package com.agent.web.mcp.installation;

import com.agent.web.capability.InstallationScope;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolRiskLevel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 已确认但尚未运行的 MCP 安装记录。 */
public record McpInstallationRecord(
        UUID installationId,
        UUID snapshotId,
        InstallationScope scope,
        UUID workspaceId,
        String actorUserId,
        McpInstallationStatus status,
        String confirmationTokenSha256,
        Instant createdAt,
        Instant confirmedAt,
        Instant updatedAt,
        ToolRiskLevel riskLevel,
        java.util.Set<RequiredCapability> requiredCapabilities,
        WorkspaceMountMode workspaceMountMode,
        McpNetworkMode networkMode,
        String runtimeImage,
        boolean runtimeImageConfirmed,
        UUID runtimeWorkspaceId,
        String containerId,
        String runtimeError,
        long version) {
    public McpInstallationRecord(
            UUID installationId, UUID snapshotId, InstallationScope scope, UUID workspaceId,
            String actorUserId, McpInstallationStatus status, String confirmationTokenSha256,
            Instant createdAt, Instant confirmedAt, Instant updatedAt) {
        this(installationId, snapshotId, scope, workspaceId, actorUserId, status,
                confirmationTokenSha256, createdAt, confirmedAt, updatedAt,
                ToolRiskLevel.HIGH, java.util.Set.of(RequiredCapability.TOOL),
                WorkspaceMountMode.NONE, McpNetworkMode.NONE, "", false, null, null, null, 0);
    }

    /** 兼容 V8 聚合调用；未确认运行镜像的记录不得启动。 */
    public McpInstallationRecord(
            UUID installationId, UUID snapshotId, InstallationScope scope, UUID workspaceId,
            String actorUserId, McpInstallationStatus status, String confirmationTokenSha256,
            Instant createdAt, Instant confirmedAt, Instant updatedAt, ToolRiskLevel riskLevel,
            java.util.Set<RequiredCapability> requiredCapabilities, WorkspaceMountMode workspaceMountMode,
            McpNetworkMode networkMode, String runtimeImage, String containerId, String runtimeError, long version) {
        this(installationId, snapshotId, scope, workspaceId, actorUserId, status, confirmationTokenSha256,
                createdAt, confirmedAt, updatedAt, riskLevel, requiredCapabilities, workspaceMountMode,
                networkMode, runtimeImage, false, null, containerId, runtimeError, version);
    }

    public McpInstallationRecord {
        Objects.requireNonNull(installationId, "installationId 不能为空");
        Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
        scope = Objects.requireNonNull(scope, "scope 不能为空");
        actorUserId = required(actorUserId, "actorUserId");
        status = Objects.requireNonNull(status, "status 不能为空");
        confirmationTokenSha256 = required(confirmationTokenSha256, "confirmationTokenSha256");
        createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt 不能为空");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        requiredCapabilities = java.util.Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities 不能为空"));
        if (!requiredCapabilities.contains(RequiredCapability.TOOL)) {
            throw new IllegalArgumentException("requiredCapabilities 必须包含 TOOL");
        }
        workspaceMountMode = Objects.requireNonNull(workspaceMountMode, "workspaceMountMode 不能为空");
        networkMode = Objects.requireNonNull(networkMode, "networkMode 不能为空");
        runtimeImage = Objects.requireNonNullElse(runtimeImage, "");
        if (runtimeImageConfirmed && runtimeImage.isBlank()) {
            throw new IllegalArgumentException("runtimeImageConfirmed 时 runtimeImage 不能为空");
        }
        if (version < 0) throw new IllegalArgumentException("version 不能小于 0");
        if (scope == InstallationScope.WORKSPACE && workspaceId == null) {
            throw new IllegalArgumentException("WORKSPACE 安装必须绑定 workspaceId");
        }
        if (scope == InstallationScope.USER_GLOBAL && workspaceId != null) {
            throw new IllegalArgumentException("USER_GLOBAL 安装不能绑定 workspaceId");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
