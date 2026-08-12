package com.agent.web.mcp.installation;

import com.agent.web.capability.CapabilityManagementAuditEvent;

import java.util.Objects;
import java.util.UUID;

/** 生命周期失败后原子清理运行标识并记录稳定失败码的命令。 */
public record McpRuntimeFailureCompletion(
        UUID installationId,
        long expectedVersion,
        String runtimeError,
        CapabilityManagementAuditEvent auditEvent) {
    public McpRuntimeFailureCompletion {
        installationId = Objects.requireNonNull(installationId, "installationId 不能为空");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion 不能小于 0");
        if (runtimeError == null || runtimeError.isBlank()) throw new IllegalArgumentException("runtimeError 不能为空");
        auditEvent = Objects.requireNonNull(auditEvent, "auditEvent 不能为空");
    }
}
