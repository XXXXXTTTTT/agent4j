package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpPreparedMaterialRecord;
import com.agent.web.mcp.installation.McpSourceSnapshot;

/** 在受治理隔离环境中准备固定 MCP 快照的离线运行物料。 */
@FunctionalInterface
public interface McpMaterialPreparationRunner {

    /** 准备固定快照，绝不执行用户提供的命令或 README 内容。 */
    McpPreparedMaterialRecord prepare(McpSourceSnapshot snapshot);
}
