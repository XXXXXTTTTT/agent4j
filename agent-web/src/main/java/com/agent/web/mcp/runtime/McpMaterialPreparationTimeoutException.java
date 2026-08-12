package com.agent.web.mcp.runtime;

/** MCP 物料准备未在受配置时限内完成。 */
public final class McpMaterialPreparationTimeoutException extends RuntimeException {
    public McpMaterialPreparationTimeoutException() {
        super("MATERIAL_PREPARATION_TIMEOUT");
    }
}
