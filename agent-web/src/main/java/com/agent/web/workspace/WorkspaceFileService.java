package com.agent.web.workspace;

import com.agent.web.audit.WorkspaceFileAuditEvent;
import com.agent.web.audit.WorkspaceFileAuditEventType;
import com.agent.web.audit.WorkspaceFileAuditSink;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 提供工作区内受路径门禁保护的文件树、读取和写入能力。 */
public final class WorkspaceFileService {

    private final WorkspaceAccessService workspaceAccess;
    private final long maxFileBytes;
    private final WorkspaceFileAuditSink auditSink;

    public WorkspaceFileService(WorkspaceAccessService workspaceAccess, long maxFileBytes) {
        this(workspaceAccess, maxFileBytes, WorkspaceFileAuditSink.noop());
    }

    public WorkspaceFileService(WorkspaceAccessService workspaceAccess, long maxFileBytes,
            WorkspaceFileAuditSink auditSink) {
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes 必须为正数");
        }
        this.maxFileBytes = maxFileBytes;
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
    }

    /** 列出工作区内一个目录的直接子项。 */
    public List<WorkspaceFileEntry> list(UUID workspaceId, String userId, String relativePath) {
        WorkspaceRecord workspace = workspaceAccess.requireWorkspace(
                workspaceId, userId, WorkspacePermission.VIEWER);
        Path directory = resolveExisting(workspace, relativePath, true);
        List<WorkspaceFileEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isSymbolicLink(entry)) {
                    continue;
                }
                Path real = entry.toRealPath();
                if (!real.startsWith(workspace.workspacePath()) || !Files.isRegularFile(real)
                        && !Files.isDirectory(real)) {
                    continue;
                }
                entries.add(entry(real, workspace.workspacePath()));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("目录读取失败", exception);
        }
        entries.sort(Comparator.comparing((WorkspaceFileEntry value) -> value.kind()
                        == WorkspaceFileEntry.Kind.DIRECTORY ? 0 : 1)
                .thenComparing(WorkspaceFileEntry::name));
        List<WorkspaceFileEntry> result = List.copyOf(entries);
        auditSink.record(new WorkspaceFileAuditEvent(WorkspaceFileAuditEventType.FILE_LISTED,
                Instant.now(), userId, workspaceId, relativePath == null ? "" : relativePath, 0, null, "SUCCESS"));
        return result;
    }

    /** 读取工作区内 UTF-8 文本文件。 */
    public WorkspaceFileContent read(UUID workspaceId, String userId, String relativePath) {
        WorkspaceRecord workspace = workspaceAccess.requireWorkspace(
                workspaceId, userId, WorkspacePermission.VIEWER);
        Path file = resolveExisting(workspace, relativePath, false);
        byte[] bytes = readBytes(file);
        WorkspaceFileContent result = content(workspace.workspacePath(), file, decodeUtf8(bytes));
        auditSink.record(new WorkspaceFileAuditEvent(WorkspaceFileAuditEventType.FILE_READ,
                Instant.now(), userId, workspaceId, result.path(), bytes.length, result.sha256(), "SUCCESS"));
        return result;
    }

    /** 按 SHA-256 乐观并发条件原子写入 UTF-8 文本文件。 */
    public WorkspaceFileContent write(UUID workspaceId, String userId, String relativePath,
            String value, String expectedSha256) {
        WorkspaceRecord workspace = workspaceAccess.requireWorkspace(
                workspaceId, userId, WorkspacePermission.OPERATOR);
        Objects.requireNonNull(value, "content 不能为空");
        Path file = resolveWritable(workspace, relativePath);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileBytes) {
            throw new FileTooLargeException(maxFileBytes);
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("目标路径不是文件");
            }
            String actual = sha256(readBytes(file));
            if (expectedSha256 != null && !expectedSha256.isBlank() && !actual.equals(expectedSha256)) {
                auditSink.record(new WorkspaceFileAuditEvent(WorkspaceFileAuditEventType.FILE_CONFLICT,
                        Instant.now(), userId, workspaceId, relativePath, 0, actual, "CONFLICT"));
                throw new FileConflictException(actual);
            }
        } else if (expectedSha256 != null && !expectedSha256.isBlank()) {
            throw new FileConflictException("");
        }
        try {
            Path temporary = Files.createTempFile(file.getParent(), ".agent4j-write-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件写入失败", exception);
        }
        WorkspaceFileContent result = read(workspaceId, userId, relativePath);
        auditSink.record(new WorkspaceFileAuditEvent(WorkspaceFileAuditEventType.FILE_WRITTEN,
                Instant.now(), userId, workspaceId, result.path(), bytes.length, result.sha256(), "SUCCESS"));
        return result;
    }

    private WorkspaceFileEntry entry(Path file, Path workspaceRoot) throws IOException {
        boolean directory = Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS);
        long size = directory ? 0 : Files.size(file);
        return new WorkspaceFileEntry(file.getFileName().toString(),
                workspaceRoot.relativize(file).toString().replace('\\', '/'),
                directory ? WorkspaceFileEntry.Kind.DIRECTORY : WorkspaceFileEntry.Kind.FILE,
                size, Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant());
    }

    private WorkspaceFileContent content(Path root, Path file, String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return new WorkspaceFileContent(root.relativize(file).toString().replace('\\', '/'),
                    value, sha256(bytes), Files.getLastModifiedTime(file).toInstant());
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件元数据读取失败", exception);
        }
    }

    private byte[] readBytes(Path file) {
        try {
            long size = Files.size(file);
            if (size > maxFileBytes) {
                throw new FileTooLargeException(maxFileBytes);
            }
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件读取失败", exception);
        }
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new BinaryFileException();
        }
    }

    private Path resolveExisting(WorkspaceRecord workspace, String relativePath, boolean directory) {
        Path path = resolve(workspace, relativePath);
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("不允许访问符号链接");
        }
        try {
            Path real = path.toRealPath();
            if (!real.startsWith(workspace.workspacePath())
                    || (directory ? !Files.isDirectory(real) : !Files.isRegularFile(real))) {
                throw new IllegalArgumentException(directory ? "目标路径不是目录" : "目标路径不是文件");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件路径不存在", exception);
        }
    }

    private Path resolveWritable(WorkspaceRecord workspace, String relativePath) {
        Path path = resolve(workspace, relativePath);
        if (path.equals(workspace.workspacePath())) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("不允许访问符号链接");
        }
        Path parent = path.getParent();
        if (parent == null || !parent.startsWith(workspace.workspacePath())) {
            throw new IllegalArgumentException("父目录必须位于工作区内");
        }
        try {
            Path existingParent = parent;
            while (existingParent != null && !Files.exists(existingParent, LinkOption.NOFOLLOW_LINKS)) {
                existingParent = existingParent.getParent();
            }
            if (existingParent == null) {
                throw new IllegalArgumentException("父目录必须位于工作区内");
            }
            Path realExistingParent = existingParent.toRealPath();
            if (!realExistingParent.startsWith(workspace.workspacePath())) {
                throw new IllegalArgumentException("父目录必须位于工作区内");
            }
            Files.createDirectories(parent);
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(workspace.workspacePath()) || !Files.isDirectory(realParent)) {
                throw new IllegalArgumentException("父目录必须位于工作区内");
            }
            return realParent.resolve(path.getFileName().toString());
        } catch (IOException exception) {
            throw new IllegalArgumentException("父目录不存在", exception);
        }
    }

    private Path resolve(WorkspaceRecord workspace, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return workspace.workspacePath();
        }
        Path requested = Path.of(relativePath);
        if (requested.isAbsolute() || requested.normalize().startsWith(Path.of(".."))) {
            throw new IllegalArgumentException("文件路径必须是工作区内的相对路径");
        }
        for (Path part : requested) {
            if (part.toString().equals("..")) {
                throw new IllegalArgumentException("文件路径必须是工作区内的相对路径");
            }
        }
        Path resolved = workspace.workspacePath().resolve(requested).normalize();
        if (!resolved.startsWith(workspace.workspacePath())) {
            throw new IllegalArgumentException("文件路径必须是工作区内的相对路径");
        }
        return resolved;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public static final class FileConflictException extends RuntimeException {
        private final String actualSha256;

        public FileConflictException(String actualSha256) {
            super("文件已被其他操作修改");
            this.actualSha256 = actualSha256;
        }

        public String actualSha256() {
            return actualSha256;
        }
    }

    public static final class FileTooLargeException extends RuntimeException {
        public FileTooLargeException(long maxFileBytes) {
            super("文件超过大小限制: " + maxFileBytes + " bytes");
        }
    }

    public static final class BinaryFileException extends RuntimeException {
        public BinaryFileException() {
            super("文件不是 UTF-8 文本");
        }
    }
}
