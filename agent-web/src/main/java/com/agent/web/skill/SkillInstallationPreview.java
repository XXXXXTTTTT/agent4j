package com.agent.web.skill;

import com.agent.web.capability.InstallationScope;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 外部 Skill 安装确认前的无副作用预览。 */
public record SkillInstallationPreview(
        UUID previewId,
        String confirmationToken,
        URI repositoryUrl,
        String repository,
        String commitSha,
        String blobSha,
        String path,
        String license,
        String contentSha256,
        String summary,
        List<String> requestedToolNames,
        InstallationScope scope,
        UUID workspaceId,
        boolean requiresConfirmation,
        boolean sideEffectFree,
        Instant expiresAt) {
    public SkillInstallationPreview {
        Objects.requireNonNull(previewId, "previewId 不能为空");
        confirmationToken = required(confirmationToken, "confirmationToken");
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
        scope = Objects.requireNonNull(scope, "scope 不能为空");
        if (scope == InstallationScope.WORKSPACE && workspaceId == null) {
            throw new IllegalArgumentException("WORKSPACE 预览必须绑定 workspaceId");
        }
        if (scope == InstallationScope.USER_GLOBAL && workspaceId != null) {
            throw new IllegalArgumentException("USER_GLOBAL 预览不能绑定 workspaceId");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt 不能为空");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
