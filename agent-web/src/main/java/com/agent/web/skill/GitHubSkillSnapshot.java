package com.agent.web.skill;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.agent.core.skill.SkillDefinition;

/** 固定 Git commit 和 blob 的外部 Skill 内容快照。 */
public record GitHubSkillSnapshot(
        URI repositoryUrl,
        String repository,
        String commitSha,
        String blobSha,
        String path,
        String license,
        String contentSha256,
        String summary,
        List<String> requestedToolNames,
        String content) {

    /** 校验不可变快照字段。 */
    public GitHubSkillSnapshot {
        repositoryUrl = Objects.requireNonNull(repositoryUrl, "repositoryUrl 不能为空");
        repository = text(repository, "repository");
        commitSha = text(commitSha, "commitSha");
        blobSha = text(blobSha, "blobSha");
        path = text(path, "path");
        license = Objects.requireNonNullElse(license, "");
        contentSha256 = text(contentSha256, "contentSha256");
        summary = text(summary, "summary");
        requestedToolNames = List.copyOf(Objects.requireNonNull(
                requestedToolNames, "requestedToolNames 不能为空"));
        content = text(content, "content");
    }

    /** 从固定正文按受控 parser 重建完整 Skill 定义。 */
    public SkillDefinition definition(Set<String> registeredToolNames) {
        return GitHubSkillContent.parse(content, registeredToolNames).definition();
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
