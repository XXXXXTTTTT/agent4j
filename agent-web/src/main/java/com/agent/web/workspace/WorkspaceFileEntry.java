package com.agent.web.workspace;

import java.time.Instant;
import java.util.Objects;

/** 工作区目录项的安全相对路径视图。 */
public record WorkspaceFileEntry(
        String name,
        String path,
        Kind kind,
        long size,
        Instant lastModified) {

    public WorkspaceFileEntry {
        requireText(name, "name");
        requireText(path, "path");
        Objects.requireNonNull(kind, "kind 不能为空");
        if (size < 0) {
            throw new IllegalArgumentException("size 不能为负数");
        }
        Objects.requireNonNull(lastModified, "lastModified 不能为空");
    }

    public enum Kind {
        DIRECTORY,
        FILE
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
