package com.agent.web.mcp.installation;

import com.agent.web.capability.InstallationScope;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 未持久化的 MCP 安装预览。 */
public record McpInstallationPreview(
        UUID previewId,
        String confirmationToken,
        InstallationScope scope,
        UUID workspaceId,
        URI sourceUrl,
        String commitSha,
        String metadataSha256,
        String command,
        List<String> arguments,
        List<String> environmentVariableNames,
        String summary,
        boolean requiresConfirmation,
        boolean sideEffectFree,
        Instant expiresAt) {
    public McpInstallationPreview {
        Objects.requireNonNull(previewId, "previewId 不能为空");
        confirmationToken = required(confirmationToken, "confirmationToken");
        scope = Objects.requireNonNull(scope, "scope 不能为空");
        sourceUrl = Objects.requireNonNull(sourceUrl, "sourceUrl 不能为空");
        commitSha = required(commitSha, "commitSha");
        metadataSha256 = required(metadataSha256, "metadataSha256");
        command = required(command, "command");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments 不能为空"));
        environmentVariableNames = List.copyOf(Objects.requireNonNull(environmentVariableNames, "environmentVariableNames 不能为空"));
        summary = Objects.requireNonNullElse(summary, "");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt 不能为空");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
