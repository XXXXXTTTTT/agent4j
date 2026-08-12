package com.agent.web.mcp.runtime;

import java.util.UUID;

/** MCP 运行物料未准备或与已确认记录不一致。 */
public final class McpMaterialNotPreparedException extends RuntimeException {
    private final UUID snapshotId;

    public McpMaterialNotPreparedException(UUID snapshotId) {
        super("MATERIAL_NOT_PREPARED");
        this.snapshotId = snapshotId;
    }

    public UUID snapshotId() {
        return snapshotId;
    }
}
