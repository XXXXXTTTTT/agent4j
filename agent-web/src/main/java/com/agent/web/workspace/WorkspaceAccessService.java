package com.agent.web.workspace;

import com.agent.web.identity.Actor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 执行工作区路径门禁和成员权限校验。 */
public final class WorkspaceAccessService {

    private final WorkspaceRepository repository;
    private final Path configuredRoot;
    private final Clock clock;

    /** 创建工作区访问服务。 */
    public WorkspaceAccessService(
            WorkspaceRepository repository,
            Path configuredRoot,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.configuredRoot = realDirectory(configuredRoot, "configuredRoot");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 返回当前用户可见的工作区。 */
    public List<WorkspaceRecord> list(Actor actor) {
        Objects.requireNonNull(actor, "actor 不能为空");
        return List.copyOf(repository.findWorkspaces(actor.userId()));
    }

    /** 创建工作区并赋予创建者 OWNER 权限。 */
    public WorkspaceRecord create(
            Actor actor,
            UUID workspaceId,
            String displayName,
            String workspacePath,
            String repositoryId) {
        Objects.requireNonNull(actor, "actor 不能为空");
        requireText(displayName, "displayName");
        requireText(repositoryId, "repositoryId");
        return repository.createWorkspace(
                Objects.requireNonNull(workspaceId, "workspaceId 不能为空"),
                actor,
                displayName.trim(),
                validateWorkspacePath(Path.of(workspacePath)),
                repositoryId.trim(),
                clock.instant());
    }

    /** 按成员权限读取工作区。无成员关系时不暴露资源存在性。 */
    public WorkspaceRecord requireWorkspace(
            UUID workspaceId,
            String userId,
            WorkspacePermission required) {
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        requireText(userId, "userId");
        Objects.requireNonNull(required, "required 不能为空");
        WorkspaceRecord workspace = repository.findWorkspace(workspaceId, userId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        if (!workspace.permission().allows(required)) {
            throw new WorkspaceAccessDeniedException(workspaceId, required);
        }
        return workspace;
    }

    /** 校验目录存在且位于配置根目录内。 */
    public Path validateWorkspacePath(Path requested) {
        Objects.requireNonNull(requested, "workspacePath 不能为空");
        try {
            Path workspace = requested.toRealPath();
            if (!Files.isDirectory(workspace) || !workspace.startsWith(configuredRoot)) {
                throw new IllegalArgumentException("workspacePath 必须位于配置工作区内");
            }
            return workspace;
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspacePath 必须是现有目录", exception);
        }
    }

    /** 幂等创建配置用户对应的默认工作区。 */
    public WorkspaceRecord ensureDefaultWorkspace(
            Actor actor,
            UUID workspaceId,
            String displayName,
            Path workspacePath,
            String repositoryId) {
        Objects.requireNonNull(actor, "actor 不能为空");
        return repository.ensureDefaultWorkspace(
                Objects.requireNonNull(workspaceId, "workspaceId 不能为空"),
                actor,
                displayName,
                validateWorkspacePath(workspacePath),
                repositoryId,
                clock.instant());
    }

    private static Path realDirectory(Path path, String name) {
        Objects.requireNonNull(path, name + " 不能为空");
        try {
            Path real = path.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException(name + " 必须是目录");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException(name + " 必须是现有目录", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }

    /** 工作区不存在或当前主体不是成员。 */
    public static final class WorkspaceNotFoundException extends RuntimeException {
        public WorkspaceNotFoundException(UUID workspaceId) {
            super("工作区不存在或当前用户无权访问: " + workspaceId);
        }
    }

    /** 当前主体权限不足。 */
    public static final class WorkspaceAccessDeniedException extends RuntimeException {
        public WorkspaceAccessDeniedException(UUID workspaceId, WorkspacePermission required) {
            super("工作区权限不足: " + workspaceId + ", required=" + required);
        }
    }
}
