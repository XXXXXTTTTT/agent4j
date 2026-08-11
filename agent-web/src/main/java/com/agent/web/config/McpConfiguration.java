package com.agent.web.config;

import com.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 生产 MCP 客户端生命周期与 ToolRegistry 装配。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(ToolRegistry.class)
@EnableConfigurationProperties(McpGatewayProperties.class)
public class McpConfiguration {

    @Bean(destroyMethod = "close")
    McpRuntime mcpRuntime(
            McpGatewayProperties properties,
            ToolRegistry registry,
            ObjectMapper objectMapper) {
        return McpRuntime.connect(properties, registry, objectMapper);
    }
}
