package com.agent.web.mcp.installation;

import com.agent.web.capability.CapabilityManagementAuditEvent;

import java.util.Objects;

/** 将 MCP 快照、安装记录和审计作为一个不可分割的持久化聚合。 */
public record McpInstallationCommand(
        McpSourceSnapshot snapshot,
        McpInstallationRecord installation,
        CapabilityManagementAuditEvent auditEvent) {
    public McpInstallationCommand {
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        installation = Objects.requireNonNull(installation, "installation 不能为空");
        auditEvent = Objects.requireNonNull(auditEvent, "auditEvent 不能为空");
        if (!snapshot.snapshotId().equals(installation.snapshotId())) {
            throw new IllegalArgumentException("snapshotId 必须与 installation.snapshotId 一致");
        }
    }
}
