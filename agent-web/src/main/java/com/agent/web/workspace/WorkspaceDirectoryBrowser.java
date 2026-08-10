package com.agent.web.workspace;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 在配置工作区根内浏览真实目录。 */
public final class WorkspaceDirectoryBrowser {

    private final Path configuredRoot;

    public WorkspaceDirectoryBrowser(Path configuredRoot) {
        this.configuredRoot = realDirectory(configuredRoot, "configuredRoot");
    }

    /** 返回已规范化的配置根目录。 */
    public Path browseRoot() {
        return configuredRoot;
    }

    /** 返回请求目录下的真实子目录。 */
    public WorkspaceDirectoryListing browse(Path requested) {
        Objects.requireNonNull(requested, "workspacePath 不能为空");
        Path current = realDirectory(requested, "workspacePath");
        if (!current.startsWith(configuredRoot)) {
            throw new IllegalArgumentException("workspacePath 必须位于配置工作区内");
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Path realEntry = entry.toRealPath();
                    if (realEntry.startsWith(configuredRoot)) {
                        entries.add(realEntry);
                    }
                } catch (IOException ignored) {
                    // 无法解析的目录不应阻断同级目录浏览。
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspacePath 目录无法读取", exception);
        }
        entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
        Path parent = current.equals(configuredRoot) ? null : current.getParent();
        return new WorkspaceDirectoryListing(current, parent, entries);
    }

    private static Path realDirectory(Path path, String name) {
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
}
