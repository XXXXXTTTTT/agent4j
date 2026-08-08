package com.agent.core.tool.mcp;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.JacksonToolSchemaValidator;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 将 MCP 远程工具转换为本地受治理工具定义。 */
public final class McpToolRegistryAdapter {

    private static final JacksonToolSchemaValidator SCHEMA_VALIDATOR = new JacksonToolSchemaValidator();

    private final McpClient client;
    private final ToolRegistry registry;

    public McpToolRegistryAdapter(McpClient client, ToolRegistry registry) {
        this.client = Objects.requireNonNull(client, "client 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
    }

    /** 握手、发现并原子注册一批具有相同治理策略的 MCP 工具。 */
    public synchronized void registerDiscoveredTools(
            String namespace,
            ToolRiskLevel riskLevel,
            Set<RequiredCapability> capabilities,
            Duration timeout) {
        validateNamespace(namespace);
        Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities 不能为空"));
        Objects.requireNonNull(timeout, "timeout 不能为空");

        client.initialize();
        List<McpRemoteTool> remoteTools = client.listTools();
        List<ToolDefinition> definitions = new ArrayList<>(remoteTools.size());
        Set<String> names = new HashSet<>();
        for (McpRemoteTool remoteTool : remoteTools) {
            String localName = namespace + "." + remoteTool.name();
            SCHEMA_VALIDATOR.validateSchema(remoteTool.inputSchema());
            ToolDefinition definition = definition(localName, remoteTool, riskLevel, capabilities, timeout);
            if (!names.add(localName)) {
                throw new IllegalArgumentException("MCP 本地工具名称重复: " + localName);
            }
            definitions.add(definition);
        }

        registry.registerAll(definitions);
    }

    private ToolDefinition definition(
            String localName,
            McpRemoteTool remoteTool,
            ToolRiskLevel riskLevel,
            Set<RequiredCapability> capabilities,
            Duration timeout) {
        JsonNode inputSchema = remoteTool.inputSchema();
        return new ToolDefinition(
                localName,
                remoteTool.description(),
                inputSchema,
                capabilities,
                riskLevel,
                timeout,
                (call, context) -> {
                    McpToolCallResult result = client.callTool(remoteTool.name(), call.arguments());
                    if (result.isError()) {
                        throw new McpRemoteToolException(remoteTool.name(), result.content());
                    }
                    return result.content();
                });
    }

    private void validateNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace 不能为空");
        }
        if (!namespace.matches("[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)*")) {
            throw new IllegalArgumentException("namespace 格式不合法");
        }
    }
}
