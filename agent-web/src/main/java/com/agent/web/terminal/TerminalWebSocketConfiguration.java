package com.agent.web.terminal;

import com.agent.core.engine.Checkpointer;
import com.agent.web.config.ProductionAgentProperties;
import com.agent.web.identity.ActorResolver;
import com.agent.web.log.InMemoryRunLogEventBus;
import com.agent.web.workspace.WorkspaceAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

import java.util.Map;

/** 装配 Run 终端 WebSocket Handler 与精确路径。 */
@Configuration(proxyBeanMethods = false)
public class TerminalWebSocketConfiguration {

    /** 创建 Run 终端 WebSocket Handler。 */
    @Bean
    RunTerminalWebSocketHandler runTerminalWebSocketHandler(
            Checkpointer checkpointer,
            InMemoryRunLogEventBus eventBus,
            ObjectMapper objectMapper) {
        return new RunTerminalWebSocketHandler(checkpointer, eventBus, objectMapper);
    }

    /** 注册精确的 Run 终端 WebSocket 路由。 */
    @Bean
    HandlerMapping terminalWebSocketHandlerMapping(RunTerminalWebSocketHandler handler) {
        Map<String, WebSocketHandler> handlers = Map.of(
                "/ws/runs/{runId}/terminal", handler);
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(handlers);
        mapping.setOrder(-1);
        return mapping;
    }

    /** 创建工作区交互终端会话服务。 */
    @Bean
    WorkspaceInteractiveTerminalService workspaceInteractiveTerminalService(
            ProductionAgentProperties properties) {
        return new WorkspaceInteractiveTerminalService(properties);
    }

    /** 创建工作区交互终端 WebSocket Handler。 */
    @Bean
    WorkspaceTerminalWebSocketHandler workspaceTerminalWebSocketHandler(
            ActorResolver actorResolver,
            WorkspaceAccessService workspaceAccess,
            WorkspaceInteractiveTerminalService terminalService,
            ObjectMapper objectMapper) {
        return new WorkspaceTerminalWebSocketHandler(
                actorResolver, workspaceAccess, terminalService, objectMapper);
    }

    /** 注册工作区交互终端的精确 WebSocket 路由。 */
    @Bean
    HandlerMapping workspaceTerminalWebSocketHandlerMapping(
            WorkspaceTerminalWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/workspaces/{workspaceId}/terminal", handler));
        mapping.setOrder(-2);
        return mapping;
    }
}
