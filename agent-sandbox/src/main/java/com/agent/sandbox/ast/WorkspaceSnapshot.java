package com.agent.sandbox.ast;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 受限 Git 工作树文本快照。 */
public record WorkspaceSnapshot(Path root, List<WorkspaceFile> files, long totalBytes) {

    /** 校验并冻结快照集合。 */
    public WorkspaceSnapshot {
        root = Objects.requireNonNull(root, "root 不能为空").toAbsolutePath().normalize();
        files = List.copyOf(Objects.requireNonNull(files, "files 不能为空"));
        if (totalBytes < 0) {
            throw new IllegalArgumentException("totalBytes 不能为负数");
        }
    }
}
