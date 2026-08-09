package com.agent.core.security;

import com.agent.core.tool.ToolCall;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolInvocationContext;

/** 工具参数安全策略端口。 */
public interface ToolParameterPolicy {

    /** 检查工具调用参数，不执行 Handler。 */
    ToolParameterDecision inspect(
            ToolDefinition definition,
            ToolCall call,
            ToolInvocationContext context);
}
