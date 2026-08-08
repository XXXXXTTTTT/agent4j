package com.agent.core.tool.mcp;

/** MCP JSON-RPC 响应违反协议时抛出的强类型异常。 */
public class McpProtocolException extends RuntimeException {

    public McpProtocolException(String message) {
        super(message);
    }

    public McpProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
