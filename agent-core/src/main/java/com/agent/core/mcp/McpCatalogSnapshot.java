package com.agent.core.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 绑定主体和工作区的不可变 MCP 工具目录快照。 */
public record McpCatalogSnapshot(
        int schemaVersion,
        String actorUserId,
        UUID workspaceId,
        Instant installationsUpdatedAt,
        List<McpToolBindingSnapshot> bindings,
        String snapshotSha256) {
    public McpCatalogSnapshot {
        if (schemaVersion != 1 && schemaVersion != 2) {
            throw new IllegalArgumentException("schemaVersion 必须为 1 或 2");
        }
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("actorUserId 不能为空");
        }
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        installationsUpdatedAt = Objects.requireNonNull(
                installationsUpdatedAt, "installationsUpdatedAt 不能为空");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings 不能为空"));
        if (snapshotSha256 == null) {
            throw new IllegalArgumentException("snapshotSha256 不能为空");
        }
        if (!snapshotSha256.isBlank() && !snapshotSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("snapshotSha256 必须是 64 位小写 SHA-256");
        }
    }
}
