package com.agent.core.skill;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 绑定主体、工作区和工具注册修订号的不可变 Skill 目录快照。 */
public record SkillCatalogSnapshot(
        int schemaVersion,
        String actorUserId,
        UUID workspaceId,
        Instant installationsUpdatedAt,
        long toolRegistryRevision,
        List<SkillDefinition> definitions,
        String snapshotSha256) {

    public SkillCatalogSnapshot {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion 必须为 1");
        }
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("actorUserId 不能为空");
        }
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        installationsUpdatedAt = Objects.requireNonNull(
                installationsUpdatedAt, "installationsUpdatedAt 不能为空");
        if (toolRegistryRevision < 0) {
            throw new IllegalArgumentException("toolRegistryRevision 不能小于 0");
        }
        definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions 不能为空"));
        if (snapshotSha256 == null) {
            throw new IllegalArgumentException("snapshotSha256 不能为空");
        }
        if (!snapshotSha256.isBlank() && !snapshotSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("snapshotSha256 必须是 64 位小写 SHA-256");
        }
    }
}
