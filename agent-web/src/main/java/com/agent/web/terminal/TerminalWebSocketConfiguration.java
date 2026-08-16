package com.agent.web.terminal;

import com.agent.core.engine.Checkpointer;
import com.agent.web.log.InMemoryRunLogEventBus;
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
}
