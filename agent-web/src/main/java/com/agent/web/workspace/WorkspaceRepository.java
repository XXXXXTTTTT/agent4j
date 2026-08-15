package com.agent.web.workspace;

import com.agent.web.identity.Actor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 工作区和成员关系的持久化端口。 */
public interface WorkspaceRepository {

    Optional<WorkspaceRecord> findWorkspace(UUID workspaceId, String userId);

    /** 按真实工作区路径查询已注册记录，用于阻止跨用户共享同一物理目录。 */
    default Optional<WorkspaceRecord> findWorkspaceByPath(Path workspacePath) {
        return Optional.empty();
    }

    List<WorkspaceRecord> findWorkspaces(String userId);

    WorkspaceRecord createWorkspace(
            UUID workspaceId,
            Actor owner,
            String displayName,
            Path workspacePath,
            String repositoryId,
            Instant now);

    WorkspaceRecord ensureDefaultWorkspace(
            UUID workspaceId,
            Actor owner,
            String displayName,
            Path workspacePath,
            String repositoryId,
            Instant now);

    void ensureUser(Actor actor, Instant now);

    void grantMember(UUID workspaceId, String userId, WorkspacePermission permission, Instant now);
}
