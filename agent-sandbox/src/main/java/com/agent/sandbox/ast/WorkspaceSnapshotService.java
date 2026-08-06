package com.agent.sandbox.ast;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.RepositoryNotFoundException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 在 Git 工作树内生成确定性、大小受限的文本快照。 */
public final class WorkspaceSnapshotService {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", "target", "node_modules", ".gradle", "build", "dist");

    private final int maxFiles;
    private final long maxBytes;

    /** 创建快照服务。 */
    public WorkspaceSnapshotService(int maxFiles, long maxBytes) {
        if (maxFiles < 1) {
            throw new IllegalArgumentException("maxFiles 必须大于 0");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes 必须大于 0");
        }
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
    }

    /** 捕获 Git 工作树中的 UTF-8 文本文件。 */
    public WorkspaceSnapshot capture(Path workspace) {
        return capture(workspace, false);
    }

    /** 捕获供模型 Prompt 使用的有界视图，超出预算的文件按稳定顺序跳过。 */
    public WorkspaceSnapshot captureForPrompt(Path workspace) {
        return capture(workspace, true);
    }

    private WorkspaceSnapshot capture(Path workspace, boolean boundedView) {
        if (workspace == null) {
            throw new NullPointerException("workspace 不能为空");
        }
        try {
            Path root = workspace.toRealPath();
            if (!Files.isDirectory(root)) {
                throw new AstServiceException("工作区不是目录: " + root);
            }
            try (Git ignored = Git.open(root.toFile())) {
                List<WorkspaceFile> files = new java.util.ArrayList<>();
                long totalBytes = 0;
                try (Stream<Path> paths = Files.walk(root)) {
                    for (Path path : paths
                            .filter(Files::isRegularFile)
                            .filter(path -> !isExcluded(root, path))
                            .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                            .toList()) {
                        if (!isUtf8Text(path)) {
                            continue;
                        }
                        if (files.size() >= maxFiles) {
                            if (boundedView) {
                                break;
                            }
                            throw new AstServiceException("工作区快照超过文件数量上限: " + maxFiles);
                        }
                        String content = Files.readString(path, StandardCharsets.UTF_8);
                        long bytes = content.getBytes(StandardCharsets.UTF_8).length;
                        if (totalBytes + bytes > maxBytes) {
                            if (boundedView) {
                                continue;
                            }
                            throw new AstServiceException("工作区快照超过字节上限: " + maxBytes);
                        }
                        files.add(new WorkspaceFile(
                                root.relativize(path).toString().replace('\\', '/'), content));
                        totalBytes += bytes;
                    }
                }
                return new WorkspaceSnapshot(root, files, totalBytes);
            }
        } catch (AstServiceException exception) {
            throw exception;
        } catch (RepositoryNotFoundException exception) {
            throw new AstServiceException("工作区不是 Git 工作树: " + workspace, exception);
        } catch (Exception exception) {
            throw new AstServiceException("读取工作区快照失败: " + workspace, exception);
        }
    }

    private boolean isExcluded(Path root, Path path) {
        String fileName = path.getFileName().toString();
        if (".env".equals(fileName)
                || fileName.endsWith(".pem")
                || fileName.endsWith(".key")) {
            return true;
        }
        Path relative = root.relativize(path);
        for (Path element : relative) {
            if (EXCLUDED_DIRECTORIES.contains(element.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isUtf8Text(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0) {
            return true;
        }
        for (byte value : bytes) {
            if (value == 0) {
                return false;
            }
        }
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.getBytes(StandardCharsets.UTF_8).length == bytes.length;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
