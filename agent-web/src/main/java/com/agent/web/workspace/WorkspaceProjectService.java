package com.agent.web.workspace;

import com.agent.web.identity.Actor;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 在配置根目录下创建并注册新的空项目。 */
public final class WorkspaceProjectService {

    private final WorkspaceAccessService workspaceAccess;
    private final Path configuredRoot;

    public WorkspaceProjectService(WorkspaceAccessService workspaceAccess, Path configuredRoot,
            java.time.Clock ignoredClock) {
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.configuredRoot = realDirectory(configuredRoot);
    }

    /** 创建单层目录项目，不覆盖已有目录。 */
    public WorkspaceRecord create(Actor actor, String displayName, String directoryName,
            String repositoryId) {
        Objects.requireNonNull(actor, "actor 不能为空");
        requireText(displayName, "displayName");
        requireText(repositoryId, "repositoryId");
        requireDirectoryName(directoryName);
        Path project = configuredRoot.resolve(directoryName).normalize();
        if (!project.getParent().equals(configuredRoot)) {
            throw new IllegalArgumentException("目录名必须是单层相对目录名");
        }
        try {
            Files.createDirectory(project);
        } catch (FileAlreadyExistsException exception) {
            throw new IllegalArgumentException("项目目录已存在", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("项目目录创建失败", exception);
        }
        try {
            return workspaceAccess.create(actor, java.util.UUID.randomUUID(), displayName,
                    project.toString(), repositoryId);
        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(project);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static Path realDirectory(Path path) {
        Objects.requireNonNull(path, "configuredRoot 不能为空");
        try {
            Path real = path.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("configuredRoot 必须是目录");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("configuredRoot 必须是现有目录", exception);
        }
    }

    private static void requireDirectoryName(String value) {
        requireText(value, "目录名");
        if (value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("目录名必须是单层相对目录名");
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
