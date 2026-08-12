package com.agent.web.mcp.runtime;

/** Python MCP 物料准备没有部署者显式配置专用镜像。 */
public final class McpMaterialPreparationImageNotConfiguredException extends RuntimeException {
    public McpMaterialPreparationImageNotConfiguredException() {
        super("MATERIAL_PREPARATION_IMAGE_NOT_CONFIGURED");
    }
}
