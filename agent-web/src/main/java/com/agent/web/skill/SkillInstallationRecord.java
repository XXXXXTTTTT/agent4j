package com.agent.web.skill;

import com.agent.web.capability.InstallationScope;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 外部 Skill 安装记录，范围和主体始终绑定。 */
public record SkillInstallationRecord(
        UUID skillInstallationId,
        UUID skillSnapshotId,
        InstallationScope scope,
        UUID workspaceId,
        String actorUserId,
        SkillInstallationStatus status,
        String confirmationTokenSha256,
        Instant createdAt,
        Instant confirmedAt,
        Instant updatedAt,
        long version) {
    public SkillInstallationRecord(
            UUID skillInstallationId, UUID skillSnapshotId, InstallationScope scope,
            UUID workspaceId, String actorUserId, SkillInstallationStatus status,
            String confirmationTokenSha256, Instant createdAt, Instant confirmedAt,
            Instant updatedAt) {
        this(skillInstallationId, skillSnapshotId, scope, workspaceId, actorUserId, status,
                confirmationTokenSha256, createdAt, confirmedAt, updatedAt, 0);
    }

    public SkillInstallationRecord {
        Objects.requireNonNull(skillInstallationId, "skillInstallationId 不能为空");
        Objects.requireNonNull(skillSnapshotId, "skillSnapshotId 不能为空");
        scope = Objects.requireNonNull(scope, "scope 不能为空");
        actorUserId = required(actorUserId, "actorUserId");
        status = Objects.requireNonNull(status, "status 不能为空");
        confirmationTokenSha256 = required(confirmationTokenSha256, "confirmationTokenSha256");
        createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt 不能为空");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
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
