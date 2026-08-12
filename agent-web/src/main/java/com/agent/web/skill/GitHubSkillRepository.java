package com.agent.web.skill;

import java.net.URI;
import java.util.Objects;

/** GitHub 搜索返回的仓库只读摘要。 */
public record GitHubSkillRepository(
        String repository,
        URI repositoryUrl,
        String defaultBranch,
        String description,
        String license) {

    /** 校验 GitHub 仓库摘要。 */
    public GitHubSkillRepository {
        repository = text(repository, "repository");
        repositoryUrl = Objects.requireNonNull(repositoryUrl, "repositoryUrl 不能为空");
        defaultBranch = text(defaultBranch, "defaultBranch");
        description = Objects.requireNonNullElse(description, "");
        license = Objects.requireNonNullElse(license, "");
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
