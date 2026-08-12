package com.agent.web.mcp.installation;

import java.util.List;
import java.util.UUID;

/** MCP 安装持久化端口。 */
public interface McpInstallationRepository {
    default McpInstallationRecord confirmInstallation(McpInstallationCommand command) {
        saveSnapshot(command.snapshot());
        McpInstallationRecord result = saveInstallation(command.installation());
        return result;
    }
    McpSourceSnapshot saveSnapshot(McpSourceSnapshot snapshot);
    McpInstallationRecord saveInstallation(McpInstallationRecord installation);
    List<McpInstallationRecord> findInstallations(String actorUserId, UUID workspaceId);
    boolean deleteInstallation(UUID installationId, String actorUserId, UUID workspaceId);

    default McpInstallationRecord transition(
            UUID installationId, long expectedVersion, McpInstallationStatus from,
            McpInstallationStatus to, String runtimeError, String containerId) {
        throw new UnsupportedOperationException("MCP 状态迁移未实现");
    }
}
