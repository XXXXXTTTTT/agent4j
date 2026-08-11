package com.agent.web.config;

import com.agent.core.intent.RequiredCapability;
import com.agent.core.tool.ToolRiskLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 生产 MCP 服务清单；每个服务必须显式声明工具治理策略。 */
@ConfigurationProperties(prefix = "agent.mcp")
public record McpGatewayProperties(
        boolean enabled,
        String protocolVersion,
        String clientName,
        String clientVersion,
        List<Server> servers) {

    public McpGatewayProperties {
        protocolVersion = text(protocolVersion, "protocolVersion");
        clientName = text(clientName, "clientName");
        clientVersion = text(clientVersion, "clientVersion");
        servers = List.copyOf(Objects.requireNonNull(servers, "servers 不能为空"));
        if (enabled && servers.isEmpty()) {
            throw new IllegalArgumentException("MCP 启用时 servers 不能为空列表");
        }
        if (servers.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("servers 不能包含 null");
        }
    }

    /** 单个 MCP 服务的连接和治理配置。 */
    public record Server(
            String namespace,
            URI endpoint,
            String authorization,
            Duration requestTimeout,
            Duration toolTimeout,
            ToolRiskLevel riskLevel,
            Set<RequiredCapability> requiredCapabilities) {

        public Server {
            namespace = text(namespace, "namespace");
            Objects.requireNonNull(endpoint, "endpoint 不能为空");
            if (!endpoint.isAbsolute()
                    || (!"http".equalsIgnoreCase(endpoint.getScheme())
                    && !"https".equalsIgnoreCase(endpoint.getScheme()))) {
                throw new IllegalArgumentException("endpoint 必须是 HTTP/HTTPS URI");
            }
            authorization = authorization == null ? "" : authorization.trim();
            positive(requestTimeout, "requestTimeout");
            positive(toolTimeout, "toolTimeout");
            Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
            requiredCapabilities = Set.copyOf(
                    Objects.requireNonNull(requiredCapabilities, "requiredCapabilities 不能为空"));
            if (requiredCapabilities.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("requiredCapabilities 不能包含 null");
            }
        }

        private static void positive(Duration value, String field) {
            Objects.requireNonNull(value, field + " 不能为空");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(field + " 必须大于 0");
            }
        }
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
