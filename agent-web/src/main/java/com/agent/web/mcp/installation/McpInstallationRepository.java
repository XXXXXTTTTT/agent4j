package com.agent.web.mcp.installation;

import java.util.List;
import java.util.UUID;

/** MCP 安装持久化端口。 */
public interface McpInstallationRepository {
    McpSourceSnapshot saveSnapshot(McpSourceSnapshot snapshot);
    McpInstallationRecord saveInstallation(McpInstallationRecord installation);
    List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId);
    boolean deleteInstallation(UUID installationId, String actorUserId, UUID workspaceId);
}
