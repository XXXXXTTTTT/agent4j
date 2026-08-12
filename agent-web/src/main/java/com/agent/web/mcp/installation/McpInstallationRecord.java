package com.agent.web.mcp.installation;

import com.agent.web.capability.InstallationScope;

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
        Instant updatedAt) {
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
