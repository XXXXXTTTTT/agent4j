package com.agent.web.mcp.installation;

import java.util.List;
import java.util.UUID;

/** MCP 安装持久化端口。 */
public interface McpInstallationRepository {
    McpInstallationRecord confirmInstallation(McpInstallationCommand command);
    List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId);
    McpInstallationRecord removeInstallation(UUID installationId, String actorUserId, UUID workspaceId,
                                             long expectedVersion,
                                             com.agent.web.capability.CapabilityManagementAuditEvent auditEvent);

    McpInstallationRecord transition(
            UUID installationId, long expectedVersion, McpInstallationStatus from,
            McpInstallationStatus to, String runtimeError, String containerId);
}
