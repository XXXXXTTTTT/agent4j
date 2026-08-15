package com.agent.web.workspace;

import java.time.Instant;
import java.util.Objects;

/** 工作区 UTF-8 文本文件内容及乐观并发摘要。 */
public record WorkspaceFileContent(
        String path,
        String content,
        String sha256,
        Instant lastModified) {

    public WorkspaceFileContent {
        requireText(path, "path");
        Objects.requireNonNull(content, "content 不能为空");
        requireText(sha256, "sha256");
        Objects.requireNonNull(lastModified, "lastModified 不能为空");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
