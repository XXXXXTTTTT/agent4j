package com.agent.sandbox.ast;

import java.util.Objects;

/** 工作区快照中的单个 UTF-8 文本文件。 */
public record WorkspaceFile(String relativePath, String content) {

    /** 校验相对路径和文件内容。 */
    public WorkspaceFile {
        if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/")) {
            throw new IllegalArgumentException("relativePath 必须是非空相对路径");
        }
        Objects.requireNonNull(content, "content 不能为空");
    }
}
