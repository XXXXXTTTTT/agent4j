package com.agent.web.skill;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 已校验的 GitHub SKILL.md 不可变快照。 */
public record SkillSnapshotRecord(
        UUID skillSnapshotId,
        URI repositoryUrl,
        String repository,
        String commitSha,
        String blobSha,
        String path,
        String license,
        String contentSha256,
        String summary,
        List<String> requestedToolNames,
        String content,
        Instant createdAt) {
    public SkillSnapshotRecord {
        Objects.requireNonNull(skillSnapshotId, "skillSnapshotId 不能为空");
        repositoryUrl = Objects.requireNonNull(repositoryUrl, "repositoryUrl 不能为空");
        repository = required(repository, "repository");
        commitSha = required(commitSha, "commitSha");
        blobSha = required(blobSha, "blobSha");
        if (!"SKILL.md".equals(path)) {
            throw new IllegalArgumentException("path 必须精确为 SKILL.md");
        }
        license = Objects.requireNonNullElse(license, "");
        contentSha256 = required(contentSha256, "contentSha256");
        summary = required(summary, "summary");
        requestedToolNames = List.copyOf(Objects.requireNonNull(
                requestedToolNames, "requestedToolNames 不能为空"));
        content = required(content, "content");
        createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
