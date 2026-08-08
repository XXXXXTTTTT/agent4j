package com.agent.core.tool.mcp;

/** MCP JSON-RPC 传输端口。 */
public interface McpTransport extends AutoCloseable {

    /** 发送带 ID 的请求并等待协议响应。 */
    McpJsonRpcResponse request(McpJsonRpcRequest request);

    /** 发送不等待响应的 JSON-RPC 通知。 */
    void notify(McpJsonRpcRequest notification);

    @Override
    default void close() {
    }
}
