package com.agent.web.workspace;

import com.agent.web.config.WorkspaceImportProperties;
import com.agent.web.identity.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** 将外部项目 ZIP 安全解压到受控导入目录并注册为工作区。 */
public final class WorkspaceImportService {

    private static final Logger AUDIT = LoggerFactory.getLogger("com.agent.audit.workspace");
    private static final int BUFFER_SIZE = 8192;
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private final WorkspaceAccessService workspaceAccess;
    private final Path configuredRoot;
    private final WorkspaceImportProperties properties;
    private final Clock clock;

    public WorkspaceImportService(
            WorkspaceAccessService workspaceAccess,
            Path configuredRoot,
            WorkspaceImportProperties properties) {
        this(workspaceAccess, configuredRoot, properties, Clock.systemUTC());
    }

    public WorkspaceImportService(
            WorkspaceAccessService workspaceAccess,
            Path configuredRoot,
            WorkspaceImportProperties properties,
            Clock clock) {
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess 不能为空");
        this.configuredRoot = realDirectory(configuredRoot);
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 在受控目录创建单次上传暂存文件。 */
    public Path createUploadFile() {
        try {
            Path uploadRoot = configuredRoot.resolve(".agent4j").resolve(".uploads");
            Files.createDirectories(uploadRoot);
            return Files.createTempFile(uploadRoot, "workspace-", ".zip");
        } catch (IOException exception) {
            throw new IllegalStateException("创建工作区上传暂存文件失败", exception);
        }
    }

    /** 删除单次上传暂存文件。 */
    public void discardUploadFile(Path uploadFile) {
        if (uploadFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(uploadFile);
        } catch (IOException exception) {
            AUDIT.error("WORKSPACE_IMPORT_UPLOAD_CLEANUP_FAILED path={} errorType={}",
                    uploadFile, exception.getClass().getName());
        }
    }

    /** 解压、原子发布并注册工作区；任何失败都会清理暂存和已发布目录。 */
    public WorkspaceRecord importArchive(
            Actor actor,
            String displayName,
            String repositoryId,
            Path archive) {
        Objects.requireNonNull(actor, "actor 不能为空");
        requireText(displayName, "displayName");
        requireText(repositoryId, "repositoryId");
        Objects.requireNonNull(archive, "archive 不能为空");
        UUID workspaceId = UUID.randomUUID();
        Path importRoot = configuredRoot.resolve(".agent4j").resolve("imports");
        Path finalDirectory = importRoot.resolve(workspaceId.toString());
        Path stagingDirectory = configuredRoot.resolve(".agent4j").resolve(".staging").resolve(workspaceId.toString());
        int files = 0;
        long extractedBytes = 0;
        long archiveBytes = -1;
        boolean published = false;
        try {
            archiveBytes = Files.size(archive);
            if (archiveBytes > properties.maxArchiveBytes()) {
                throw new ImportLimitExceededException("ZIP 文件超过大小上限");
            }
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                throw new ImportFormatException("上传内容必须是普通 ZIP 文件");
            }
            if (!hasZipSignature(archive)) {
                throw new ImportFormatException("上传内容不是有效 ZIP 文件");
            }
            if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new ImportConflictException("工作区导入目标已存在: " + workspaceId);
            }
            Files.createDirectories(stagingDirectory);
            Set<Path> normalizedEntries = new HashSet<>();
            try (InputStream input = Files.newInputStream(archive);
                 ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                byte[] buffer = new byte[BUFFER_SIZE];
                while ((entry = zip.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    Path relative = normalizeEntry(entryName);
                    if (!normalizedEntries.add(relative)) {
                        throw new ImportFormatException("ZIP 条目路径重复: " + entryName);
                    }
                    Path target = stagingDirectory.resolve(relative).normalize();
                    if (!target.startsWith(stagingDirectory)) {
                        throw new ImportFormatException("ZIP 条目路径越界: " + entryName);
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        zip.closeEntry();
                        continue;
                    }
                    files++;
                    if (files > properties.maxFiles()) {
                        throw new ImportLimitExceededException("ZIP 文件数量超过上限");
                    }
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            if (read == 0) {
                                continue;
                            }
                            extractedBytes += read;
                            if (extractedBytes > properties.maxExtractedBytes()) {
                                throw new ImportLimitExceededException("解压后文件总大小超过上限");
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                    zip.closeEntry();
                }
            } catch (ZipException exception) {
                throw new ImportFormatException("上传内容不是有效 ZIP 文件", exception);
            }
            Files.createDirectories(importRoot);
            moveAtomically(stagingDirectory, finalDirectory);
            published = true;
            WorkspaceRecord result = workspaceAccess.create(
                    actor, workspaceId, displayName, finalDirectory.toString(), repositoryId);
            AUDIT.info("WORKSPACE_IMPORT_COMPLETED user={} workspace={} files={} archiveBytes={} extractedBytes={} status=COMPLETED time={}",
                    actor.userId(), workspaceId, files, archiveBytes, extractedBytes, beijingNow());
            return result;
        } catch (RuntimeException | IOException exception) {
            deleteTree(stagingDirectory);
            if (published) {
                deleteTree(finalDirectory);
            }
            AUDIT.warn("WORKSPACE_IMPORT_FAILED user={} workspace={} files={} extractedBytes={} status={} errorType={} time={}",
                    actor.userId(), workspaceId, files, extractedBytes, exception.getClass().getSimpleName(), exception.getClass().getName(), beijingNow());
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ImportFormatException("读取 ZIP 文件失败", exception);
        }
    }

    private OffsetDateTime beijingNow() {
        return OffsetDateTime.ofInstant(clock.instant(), BEIJING_ZONE);
    }

    private static Path normalizeEntry(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw new ImportFormatException("ZIP 条目路径不能为空");
        }
        String normalizedSeparators = entryName.replace('\\', '/');
        if (normalizedSeparators.startsWith("/")
                || normalizedSeparators.startsWith("\\")
                || (normalizedSeparators.length() >= 2 && normalizedSeparators.charAt(1) == ':')) {
            throw new ImportFormatException("ZIP 条目路径必须是相对路径: " + entryName);
        }
        Path relative = Path.of(normalizedSeparators).normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0
                || relative.startsWith(Path.of("..")) || relative.toString().equals(".")) {
            throw new ImportFormatException("ZIP 条目路径越界: " + entryName);
        }
        return relative;
    }

    private static boolean hasZipSignature(Path archive) throws IOException {
        try (InputStream input = Files.newInputStream(archive)) {
            byte[] signature = input.readNBytes(4);
            if (signature.length < 4 || signature[0] != 'P' || signature[1] != 'K') {
                return false;
            }
            return (signature[2] == 3 && signature[3] == 4)
                    || (signature[2] == 5 && signature[3] == 6)
                    || (signature[2] == 7 && signature[3] == 8);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static Path realDirectory(Path root) {
        try {
            Path real = Objects.requireNonNull(root, "configuredRoot 不能为空").toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("configuredRoot 必须是目录");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("configuredRoot 必须是现有目录", exception);
        }
    }

    private static void deleteTree(Path root) {
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
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            AUDIT.error("WORKSPACE_IMPORT_CLEANUP_FAILED path={} errorType={}", root, exception.getClass().getName());
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }

    public static class ImportFormatException extends RuntimeException {
        public ImportFormatException(String message) { super(message); }
        public ImportFormatException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class ImportLimitExceededException extends RuntimeException {
        public ImportLimitExceededException(String message) { super(message); }
    }

    public static final class ImportConflictException extends RuntimeException {
        public ImportConflictException(String message) { super(message); }
    }
}
