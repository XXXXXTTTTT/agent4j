package com.agent.web.mcp.installation;

/** MCP 安装持久化端口。 */
public interface McpInstallationRepository {
    McpSourceSnapshot saveSnapshot(McpSourceSnapshot snapshot);
    McpInstallationRecord saveInstallation(McpInstallationRecord installation);
}
