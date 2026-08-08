package com.agent.core.tool.mcp;

/** MCP 传输层失败时抛出的强类型异常。 */
public class McpTransportException extends RuntimeException {

    public McpTransportException(String message) {
        super(message);
    }

    public McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
