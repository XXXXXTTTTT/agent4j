package com.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** 单个已注册工具的执行端口。 */
@FunctionalInterface
public interface ToolHandler {

    /** 执行工具并返回结构化 JSON。 */
    JsonNode execute(ToolCall call, ToolInvocationContext context) throws Exception;
}
