package com.agent.rag.ingest;

import java.nio.file.Path;
import java.util.Objects;

/** 仓库快照中的一份不可变 UTF-8 源文件。 */
public record RepositorySource(
        String relativePath,
        String content,
        String contentSha256) {

    /** 校验相对路径、正文和内容哈希。 */
    public RepositorySource {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }
        Path path = Path.of(relativePath);
        String normalized = path.normalize().toString().replace('\\', '/');
        if (path.isAbsolute()
                || relativePath.indexOf('\\') >= 0
                || normalized.equals(".")
                || normalized.startsWith("../")
                || !normalized.equals(relativePath)) {
            throw new IllegalArgumentException(
                    "relativePath 必须是规范化的 / 分隔仓库相对路径");
        }
        content = Objects.requireNonNull(content, "content 不能为空");
        if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 必须是 64 位小写 SHA-256");
        }
    }
}
