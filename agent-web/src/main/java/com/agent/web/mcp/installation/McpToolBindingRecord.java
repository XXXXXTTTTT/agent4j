package com.agent.web.mcp.installation;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolRiskLevel;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 已注册 MCP 远端工具到本地受治理工具的固定绑定。 */
public record McpToolBindingRecord(
        UUID installationId,
        String localToolName,
        String remoteToolName,
        ToolRiskLevel riskLevel,
        Set<RequiredCapability> requiredCapabilities,
        Instant createdAt) {
    public McpToolBindingRecord {
        installationId = Objects.requireNonNull(installationId, "installationId 不能为空");
        localToolName = text(localToolName, "localToolName");
        remoteToolName = text(remoteToolName, "remoteToolName");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities 不能为空"));
        if (!requiredCapabilities.contains(RequiredCapability.TOOL)) {
            throw new IllegalArgumentException("requiredCapabilities 必须包含 TOOL");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
