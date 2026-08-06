package com.agent.web.controller;

import com.agent.web.workspace.WorkspaceRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 工作区只读 HTTP 视图。 */
public record WorkspaceView(
        UUID workspaceId,
        String ownerUserId,
        String displayName,
        Path workspacePath,
        String repositoryId,
        String permission,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkspaceView from(WorkspaceRecord workspace) {
        Objects.requireNonNull(workspace, "workspace 不能为空");
        return new WorkspaceView(
                workspace.workspaceId(), workspace.ownerUserId(), workspace.displayName(),
                workspace.workspacePath(), workspace.repositoryId(), workspace.permission().name(),
                workspace.createdAt(), workspace.updatedAt());
    }
}
