package com.agent.web.mcp.installation;

import com.agent.web.capability.CapabilityManagementAuditEvent;

import java.util.Objects;
import java.util.UUID;

/** 停止成功后原子删除绑定、清理运行标识和更新状态的命令。 */
public record McpRuntimeStopCompletion(
        UUID installationId,
        long expectedVersion,
        CapabilityManagementAuditEvent auditEvent) {
    public McpRuntimeStopCompletion {
        installationId = Objects.requireNonNull(installationId, "installationId 不能为空");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion 不能小于 0");
        auditEvent = Objects.requireNonNull(auditEvent, "auditEvent 不能为空");
    }
}
