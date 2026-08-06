package com.agent.web.workspace;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 当前用户可见的工作区只读记录。 */
public record WorkspaceRecord(
        UUID workspaceId,
        String ownerUserId,
        String displayName,
        Path workspacePath,
        String repositoryId,
        WorkspacePermission permission,
        Instant createdAt,
        Instant updatedAt) {

    /** 校验工作区记录。 */
    public WorkspaceRecord {
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        requireText(ownerUserId, "ownerUserId");
        requireText(displayName, "displayName");
        Objects.requireNonNull(workspacePath, "workspacePath 不能为空");
        requireText(repositoryId, "repositoryId");
        Objects.requireNonNull(permission, "permission 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
