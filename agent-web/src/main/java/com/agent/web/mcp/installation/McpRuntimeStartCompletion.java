package com.agent.web.mcp.installation;

import com.agent.web.capability.CapabilityManagementAuditEvent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 启动成功后原子写入绑定、容器标识、状态和审计的命令。 */
public record McpRuntimeStartCompletion(
        UUID installationId,
        long expectedVersion,
        UUID runtimeWorkspaceId,
        String containerId,
        List<McpToolBindingRecord> bindings,
        CapabilityManagementAuditEvent auditEvent) {
    public McpRuntimeStartCompletion {
        installationId = Objects.requireNonNull(installationId, "installationId 不能为空");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion 不能小于 0");
        runtimeWorkspaceId = Objects.requireNonNull(runtimeWorkspaceId, "runtimeWorkspaceId 不能为空");
        if (containerId == null || containerId.isBlank()) throw new IllegalArgumentException("containerId 不能为空");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings 不能为空"));
        for (McpToolBindingRecord binding : bindings) {
            if (!installationId.equals(binding.installationId())) {
                throw new IllegalArgumentException("binding.installationId 必须一致");
            }
        }
        auditEvent = Objects.requireNonNull(auditEvent, "auditEvent 不能为空");
    }
}
