package com.agent.core.mcp;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolRiskLevel;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Run 启动时冻结的一条 MCP 工具绑定。 */
public record McpToolBindingSnapshot(
        UUID installationId,
        UUID snapshotId,
        long installationVersion,
        UUID runtimeBindingInstanceId,
        long runtimeBindingRevision,
        String localToolName,
        String remoteToolName,
        ToolRiskLevel riskLevel,
        Set<RequiredCapability> requiredCapabilities,
        Instant bindingCreatedAt) {
    public McpToolBindingSnapshot {
        installationId = Objects.requireNonNull(installationId, "installationId 不能为空");
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
        if (installationVersion < 0) {
            throw new IllegalArgumentException("installationVersion 不能小于 0");
        }
        runtimeBindingInstanceId = Objects.requireNonNull(
                runtimeBindingInstanceId, "runtimeBindingInstanceId 不能为空");
        if (runtimeBindingRevision < 0) {
            throw new IllegalArgumentException("runtimeBindingRevision 不能小于 0");
        }
        localToolName = required(localToolName, "localToolName");
        remoteToolName = required(remoteToolName, "remoteToolName");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities 不能为空"));
        if (!requiredCapabilities.contains(RequiredCapability.TOOL)) {
            throw new IllegalArgumentException("requiredCapabilities 必须包含 TOOL");
        }
        bindingCreatedAt = Objects.requireNonNull(bindingCreatedAt, "bindingCreatedAt 不能为空");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
