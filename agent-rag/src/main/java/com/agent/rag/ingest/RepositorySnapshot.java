package com.agent.rag.ingest;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 一次仓库扫描得到的同源不可变正文与内容指纹。 */
public record RepositorySnapshot(
        Path root,
        List<RepositorySource> sources,
        String fingerprint) {

    /** 规范化根路径、冻结来源并校验稳定指纹。 */
    public RepositorySnapshot {
        root = Objects.requireNonNull(root, "root 不能为空")
                .toAbsolutePath()
                .normalize();
        sources = Objects.requireNonNull(sources, "sources 不能为空").stream()
                .map(source -> Objects.requireNonNull(source, "sources 不能包含 null"))
                .sorted(Comparator.comparing(RepositorySource::relativePath))
                .toList();
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint 必须是 64 位小写 SHA-256");
        }
    }
}
