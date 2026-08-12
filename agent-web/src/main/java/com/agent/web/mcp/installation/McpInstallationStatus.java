package com.agent.web.mcp.installation;

/** MCP 安装生命周期状态。 */
public enum McpInstallationStatus {
    PREVIEW,
    PENDING_APPROVAL,
    INSTALLING,
    RUNNING,
    FAILED,
    STOPPING,
    STOPPED,
    REJECTED
}
