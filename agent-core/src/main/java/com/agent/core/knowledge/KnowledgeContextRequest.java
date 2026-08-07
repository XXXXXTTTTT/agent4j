package com.agent.core.knowledge;

import com.agent.core.intent.TaskComplexity;

import java.nio.file.Path;
import java.util.Objects;

/** 请求一次受工作区边界约束的项目知识加载。 */
public record KnowledgeContextRequest(
        String repositoryId,
        String userId,
        Path workspaceRoot,
        Path activePath,
        String query,
        TaskComplexity complexity,
        int maxTokens) {

    /** 校验文本、路径边界和 token 预算。 */
    public KnowledgeContextRequest {
        repositoryId = requireText(repositoryId, "repositoryId");
        userId = requireText(userId, "userId");
        workspaceRoot = normalize(Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空"));
        activePath = normalize(Objects.requireNonNull(activePath, "activePath 不能为空"));
        if (!activePath.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("activePath 必须位于 workspaceRoot 内");
        }
        query = requireText(query, "query");
        Objects.requireNonNull(complexity, "complexity 不能为空");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须大于 0");
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
