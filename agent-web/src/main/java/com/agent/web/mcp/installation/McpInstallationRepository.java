package com.agent.web.mcp.installation;

import java.util.List;
import java.util.UUID;

/** MCP 安装持久化端口。 */
public interface McpInstallationRepository {
    McpInstallationRecord confirmInstallation(McpInstallationCommand command);
    McpSourceSnapshot saveSnapshot(McpSourceSnapshot snapshot);
    McpInstallationRecord saveInstallation(McpInstallationRecord installation);
    List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId);
    boolean deleteInstallation(UUID installationId, String actorUserId, UUID workspaceId, long expectedVersion);

    McpInstallationRecord transition(
            UUID installationId, long expectedVersion, McpInstallationStatus from,
            McpInstallationStatus to, String runtimeError, String containerId);
}
