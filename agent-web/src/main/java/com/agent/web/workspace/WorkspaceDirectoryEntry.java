package com.agent.web.workspace;

import java.util.Objects;

/** 工作区目录浏览的单个目录项。 */
public record WorkspaceDirectoryEntry(String name, String path) {

    public WorkspaceDirectoryEntry {
        if (Objects.requireNonNull(name, "name 不能为空").isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
        if (Objects.requireNonNull(path, "path 不能为空").isBlank()) {
            throw new IllegalArgumentException("path 不能为空白");
        }
    }
}
