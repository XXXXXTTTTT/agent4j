package com.agent.core.mcp;

import java.util.UUID;

/** 按可信 Run 身份解析即将冻结的 MCP 工具目录。 */
@FunctionalInterface
public interface McpCatalogProvider {
    McpCatalogSnapshot resolve(String actorUserId, UUID workspaceId);
}
