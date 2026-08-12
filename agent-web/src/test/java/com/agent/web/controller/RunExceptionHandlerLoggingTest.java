package com.agent.web.controller;

import com.agent.web.audit.AuditTextRedactor;
import com.agent.web.mcp.runtime.McpMaterialPreparationTimeoutException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class RunExceptionHandlerLoggingTest {

    @Test
    void mapsMaterialPreparationTimeoutToStableConflictCode() {
        var response = new RunExceptionHandler(new AuditTextRedactor(java.util.List.of()))
                .mcpMaterialPreparationTimeout(new McpMaterialPreparationTimeoutException(),
                        MockServerWebExchange.from(MockServerHttpRequest.post("/api/workspaces/1/mcp/installations/2/material")));

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(response.getBody().getDetail()).isEqualTo("MATERIAL_PREPARATION_TIMEOUT");
    }

    @Test
    void logsUnhandledExceptionWithRequestMethodAndPath() {
        Logger logger = (Logger) LoggerFactory.getLogger(RunExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            IllegalStateException failure = new IllegalStateException(
                    "database unavailable OPENAI_API_KEY=exception-secret");
            new RunExceptionHandler(new AuditTextRedactor(java.util.List.of())).internalServerError(
                    failure,
                    MockServerWebExchange.from(MockServerHttpRequest.get("/api/runs/42")));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getFormattedMessage())
                    .contains("GET")
                    .contains("/api/runs/42")
                    .contains("RunExceptionHandlerLoggingTest.java")
                    .contains("OPENAI_API_KEY=[REDACTED]")
                    .doesNotContain("exception-secret");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
