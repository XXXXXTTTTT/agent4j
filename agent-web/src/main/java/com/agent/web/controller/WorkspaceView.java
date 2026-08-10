package com.agent.web.controller;

import com.agent.web.workspace.WorkspaceRecord;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 工作区只读 HTTP 视图。 */
public record WorkspaceView(
        UUID workspaceId,
        String ownerUserId,
        String displayName,
        String workspacePath,
        String repositoryId,
        String permission,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkspaceView from(WorkspaceRecord workspace) {
        Objects.requireNonNull(workspace, "workspace 不能为空");
        return new WorkspaceView(
                workspace.workspaceId(), workspace.ownerUserId(), workspace.displayName(),
                serverPath(workspace), workspace.repositoryId(), workspace.permission().name(),
                workspace.createdAt(), workspace.updatedAt());
    }

    private static String serverPath(WorkspaceRecord workspace) {
        String path = workspace.workspacePath().toString();
        String separator = workspace.workspacePath().getFileSystem().getSeparator();
        return path.replace(separator, "/");
    }
}
