package com.agent.core.tool.mcp;

import java.io.InputStream;
import java.io.OutputStream;

/** MCP stdio 进程端口，由 Docker 运行器提供具体实现。 */
public interface McpStdioProcess {

    InputStream stdout();

    OutputStream stdin();

    InputStream stderr();

    boolean isAlive();

    void destroy();
}
