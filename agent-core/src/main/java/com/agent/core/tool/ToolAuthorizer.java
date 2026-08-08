package com.agent.core.tool;

/** 工具授权策略端口。 */
@FunctionalInterface
public interface ToolAuthorizer {

    /** 根据定义与调用上下文返回授权决策。 */
    ToolAuthorization authorize(
            ToolDefinition definition,
            ToolCall call,
            ToolInvocationContext context);
}
