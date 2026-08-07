package com.agent.rag.knowledge;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** 已读取项目知识文件的不可变来源元数据。 */
public record KnowledgeSource(
        String relativePath,
        KnowledgeFileType fileType,
        int depth,
        int byteCount,
        int lineCount,
        String sha256) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** 校验相对路径、文件限制和内容指纹。 */
    public KnowledgeSource {
        relativePath = requireRelativePath(relativePath);
        Objects.requireNonNull(fileType, "fileType 不能为空");
        if (depth < 0) {
            throw new IllegalArgumentException("depth 不能为负数");
        }
        if (byteCount < 0 || byteCount > 25_000) {
            throw new IllegalArgumentException("byteCount 必须在 0 到 25000 之间");
        }
        if (lineCount < 1 || lineCount > 200) {
            throw new IllegalArgumentException("lineCount 必须在 1 到 200 之间");
        }
        sha256 = Objects.requireNonNull(sha256, "sha256 不能为空");
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 必须是 64 位小写十六进制字符串");
        }
    }

    private static String requireRelativePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }
        if (value.indexOf('\\') >= 0 || value.startsWith("/") || Path.of(value).isAbsolute()) {
            throw new IllegalArgumentException("relativePath 必须是使用 / 分隔的相对路径");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals("..")) {
                throw new IllegalArgumentException("relativePath 包含无效路径段");
            }
        }
        return value;
    }
}
