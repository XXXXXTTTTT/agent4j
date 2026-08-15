package com.agent.web.workspace;

import com.agent.web.identity.Actor;
import com.agent.web.audit.WorkspaceFileAuditEvent;
import com.agent.web.audit.WorkspaceFileAuditEventType;
import com.agent.web.audit.WorkspaceFileAuditSink;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.Objects;

/** 在配置根目录下创建并注册新的空项目。 */
public final class WorkspaceProjectService {

    private final WorkspaceAccessService workspaceAccess;
    private final Path configuredRoot;
    private final WorkspaceFileAuditSink auditSink;
    private final Clock clock;

    public WorkspaceProjectService(WorkspaceAccessService workspaceAccess, Path configuredRoot,
            Clock clock) {
        this(workspaceAccess, configuredRoot, clock, WorkspaceFileAuditSink.noop());
    }

    public WorkspaceProjectService(WorkspaceAccessService workspaceAccess, Path configuredRoot,
            Clock clock, WorkspaceFileAuditSink auditSink) {
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.configuredRoot = realDirectory(configuredRoot);
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
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
            try (Git ignored = Git.init().setDirectory(project.toFile()).call()) {
                // 空项目必须先成为 Git 工作树，CoderNode 才能建立 AST 快照和 Diff。
            }
        } catch (FileAlreadyExistsException exception) {
            throw new IllegalArgumentException("项目目录已存在", exception);
        } catch (GitAPIException | IOException exception) {
            throw new IllegalArgumentException("项目 Git 初始化失败", exception);
        }
        try {
            WorkspaceRecord workspace = workspaceAccess.create(actor, java.util.UUID.randomUUID(), displayName,
                    project.toString(), repositoryId);
            auditSink.record(new WorkspaceFileAuditEvent(WorkspaceFileAuditEventType.PROJECT_CREATED,
                    clock.instant(), actor.userId(), workspace.workspaceId(), directoryName, 0, null, "SUCCESS"));
            return workspace;
        } catch (RuntimeException exception) {
            deleteTree(project, exception);
            throw exception;
        }
    }

    /** 注册失败时递归删除已初始化的 Git 工作树，避免留下半成品项目。 */
    private static void deleteTree(Path root, RuntimeException original) {
        if (root == null || Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                        throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
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
