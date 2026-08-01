package com.agent.web.trace;

import com.agent.core.engine.Checkpointer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/** 装配 Run Trace WebSocket Handler 与精确路径。 */
@Configuration(proxyBeanMethods = false)
public class TraceWebSocketConfiguration {

    /** 创建 Run Trace WebSocket Handler。 */
    @Bean
    RunTraceWebSocketHandler runTraceWebSocketHandler(
            Checkpointer checkpointer,
            InMemoryTraceEventBus eventBus,
            ObjectMapper objectMapper) {
        return new RunTraceWebSocketHandler(checkpointer, eventBus, objectMapper);
    }

    /** 注册精确的 Run Trace WebSocket 路由。 */
    @Bean
    HandlerMapping traceWebSocketHandlerMapping(RunTraceWebSocketHandler handler) {
        Map<String, WebSocketHandler> handlers = Map.of(
                "/ws/runs/{runId}/trace", handler);
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(handlers);
        mapping.setOrder(-1);
        return mapping;
    }

    /** 创建 WebSocket 升级适配器。 */
    @Bean
    WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
