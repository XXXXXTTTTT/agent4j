package com.agent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** 生产 Code Agent 的工作区、执行目标和资源上限配置。 */
@ConfigurationProperties(prefix = "agent.production")
public record ProductionAgentProperties(
        boolean enabled,
        Path workspace,
        String repositoryId,
        String userId,
        String reviewerUrl,
        String executionMode,
        String bashExecutable,
        String dockerImage,
        String containerWorkspace,
        String workspaceSourceContainer,
        String workspaceSourcePath,
        Duration commandTimeout,
        Duration browserTimeout,
        int snapshotMaxFiles,
        long snapshotMaxBytes,
        int maxRepairAttempts,
        int maxSteps,
        int plannerContextMaxTokens) {

    /** 冻结配置文本并校验生产图所需的精确值。 */
    public ProductionAgentProperties {
        reviewerUrl = reviewerUrl == null ? "" : reviewerUrl.trim();
        repositoryId = text(repositoryId, "repositoryId");
        userId = text(userId, "userId");
        executionMode = text(executionMode, "executionMode");
        bashExecutable = text(bashExecutable, "bashExecutable");
        dockerImage = text(dockerImage, "dockerImage");
        containerWorkspace = text(containerWorkspace, "containerWorkspace");
        workspaceSourceContainer = optionalText(workspaceSourceContainer);
        workspaceSourcePath = optionalText(workspaceSourcePath);
        if (workspaceSourceContainer.isBlank() != workspaceSourcePath.isBlank()) {
            throw new IllegalArgumentException(
                    "workspaceSourceContainer 与 workspaceSourcePath 必须同时配置");
        }
        Objects.requireNonNull(workspace, "workspace 不能为空");
        positive(commandTimeout, "commandTimeout");
        positive(browserTimeout, "browserTimeout");
        if (!"DOCKER".equals(executionMode) && !"PTY".equals(executionMode)) {
            throw new IllegalArgumentException("executionMode 必须精确为 DOCKER 或 PTY");
        }
        if (snapshotMaxFiles < 1) {
            throw new IllegalArgumentException("snapshotMaxFiles 必须大于 0");
        }
        if (snapshotMaxBytes < 1) {
            throw new IllegalArgumentException("snapshotMaxBytes 必须大于 0");
        }
        if (maxRepairAttempts < 1) {
            throw new IllegalArgumentException("maxRepairAttempts 必须大于 0");
        }
        if (maxSteps < 4) {
            throw new IllegalArgumentException("maxSteps 必须至少为 4");
        }
        if (plannerContextMaxTokens < 1) {
            throw new IllegalArgumentException("plannerContextMaxTokens 必须大于 0");
        }
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}
