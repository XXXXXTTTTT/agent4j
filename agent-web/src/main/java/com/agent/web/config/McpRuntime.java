package com.agent.web.config;

import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.mcp.McpClient;
import com.agent.core.tool.mcp.McpHttpTransport;
import com.agent.core.tool.mcp.McpToolRegistryAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将配置的 MCP 服务发现并接入现有 ToolRegistry 治理边界。 */
public final class McpRuntime implements AutoCloseable {

    private final List<McpClient> clients;

    private McpRuntime(List<McpClient> clients) {
        this.clients = List.copyOf(clients);
    }

    /** 完成全部服务握手和工具发现；任一服务失败则整体失败并关闭已建立连接。 */
    public static McpRuntime connect(
            McpGatewayProperties properties,
            ToolRegistry registry,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(properties, "properties 不能为空");
        Objects.requireNonNull(registry, "registry 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        if (!properties.enabled()) {
            return new McpRuntime(List.of());
        }
        List<McpClient> clients = new ArrayList<>();
        try {
            for (McpGatewayProperties.Server server : properties.servers()) {
                RestClient.Builder builder = RestClient.builder();
                if (!server.authorization().isBlank()) {
                    builder.defaultHeader("Authorization", server.authorization());
                }
                McpHttpTransport transport = new McpHttpTransport(
                        builder.build(), objectMapper, server.endpoint(), server.requestTimeout());
                McpClient client = new McpClient(
                        transport,
                        objectMapper,
                        properties.protocolVersion(),
                        properties.clientName(),
                        properties.clientVersion());
                new McpToolRegistryAdapter(client, registry).registerDiscoveredTools(
                        server.namespace(),
                        server.riskLevel(),
                        server.requiredCapabilities(),
                        server.toolTimeout());
                clients.add(client);
            }
            return new McpRuntime(clients);
        } catch (RuntimeException failure) {
            clients.forEach(McpClient::close);
            throw failure;
        }
    }

    @Override
    public void close() {
        clients.reversed().forEach(McpClient::close);
    }
}
