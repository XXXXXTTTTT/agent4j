package com.agent.web.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 已通过工作区根目录门禁的目录列表。 */
public record WorkspaceDirectoryListing(
        Path currentPath,
        Path parentPath,
        List<Path> entries) {

    public WorkspaceDirectoryListing {
        Objects.requireNonNull(currentPath, "currentPath 不能为空");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries 不能为空"));
    }
}
