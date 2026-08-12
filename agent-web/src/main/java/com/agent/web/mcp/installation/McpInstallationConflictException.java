package com.agent.web.mcp.installation;

import java.util.UUID;

/** MCP 安装的版本或生命周期状态与当前持久化记录不一致。 */
public final class McpInstallationConflictException extends RuntimeException {
    public McpInstallationConflictException(UUID installationId, long expectedVersion) {
        super("MCP 安装版本或状态冲突: " + installationId + ", expectedVersion=" + expectedVersion);
    }
}
