package com.agent.web.terminal;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunNotFoundException;
import com.agent.core.engine.RunStatus;
import com.agent.core.nodes.OpsNode;
import com.agent.core.trace.RunLogEvent;
import com.agent.core.trace.RunLogStream;
import com.agent.web.controller.RunExceptionHandler;
import com.agent.web.log.InMemoryRunLogEventBus;
import com.agent.web.log.RunLogSubscription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = RunTerminalController.class)
@Import({RunExceptionHandler.class, RunTerminalControllerTest.TestBeans.class})
class RunTerminalControllerTest {

    private static final UUID RUN_ID = UUID.fromString(
            "5f7257dc-086f-4d2a-82d4-0ce8f47016ae");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-02T02:00:00Z");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryRunLogEventBus eventBus;

    @MockBean
    private AgentRunService runService;

    @Test
    void sendsSnapshotThenBufferedLogWithExactSseMetadata() throws Exception {
        RunLogEvent log = new RunLogEvent(
                UUID.fromString("cfe232a4-2ccd-42f4-b8bf-f06219b84f93"),
                RUN_ID,
                "ops",
                4,
                RunLogStream.PTY,
                "\u001b[31mfail\u001b[0m",
                OCCURRED_AT.plusSeconds(1));
        when(runService.get(RUN_ID)).thenAnswer(invocation -> {
            eventBus.publish(log);
            eventBus.complete(RUN_ID);
            return checkpoint(terminalState());
        });

        byte[] responseBody = webTestClient.get()
                .uri("/api/runs/{runId}/logs", RUN_ID)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody()
                .returnResult()
                .getResponseBody();

        String body = new String(responseBody, StandardCharsets.UTF_8);
        assertThat(body.lines().filter(line -> line.startsWith("id:")))
                .containsExactly("id:3", "id:" + log.eventId());
        assertThat(body.lines().filter(line -> line.startsWith("event:")))
                .containsExactly("event:snapshot", "event:log");
        List<JsonNode> frames = body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> readJson(line.substring("data:".length())))
                .toList();
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).path("kind").textValue()).isEqualTo("SNAPSHOT");
        assertThat(frames.get(0).path("terminal").path("stdout").textValue())
                .isEqualTo("\u001b[32mok\u001b[0m");
        assertThat(frames.get(0).path("terminal").path("exitCode").intValue())
                .isZero();
        assertThat(frames.get(0).path("terminal").path("timedOut").booleanValue())
                .isFalse();
        assertThat(frames.get(1).path("kind").textValue()).isEqualTo("LOG");
        assertThat(frames.get(1).path("event").path("text").textValue())
                .isEqualTo("\u001b[31mfail\u001b[0m");
    }

    @Test
    void mapsUnknownRunToNotFound() {
        UUID missing = UUID.fromString("419008be-094f-4582-a27e-e2b164b49cad");
        when(runService.get(missing)).thenThrow(new RunNotFoundException(missing));

        webTestClient.get()
                .uri("/api/runs/{runId}/logs", missing)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Run 不存在: " + missing);
    }

    @Test
    void terminalSnapshotUsesNullForMissingResultsAndRejectsInvalidValues() {
        TerminalSnapshot empty = TerminalSnapshot.from(checkpoint(AgentState.empty()));

        assertThat(empty.stdout()).isEmpty();
        assertThat(empty.stderr()).isEmpty();
        assertThat(empty.exitCode()).isNull();
        assertThat(empty.timedOut()).isNull();
        assertThat(empty.error()).isNull();
        assertThatThrownBy(() -> TerminalSnapshot.from(checkpoint(
                AgentState.empty().withVariable(OpsNode.EXIT_CODE_KEY, "zero"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TerminalSnapshot.from(checkpoint(
                AgentState.empty().withVariable(OpsNode.TIMED_OUT_KEY, "FALSE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(OpsNode.TIMED_OUT_KEY);
    }

    @Test
    void closesSubscriptionWhenTerminalSnapshotIsInvalid() {
        AgentRunService service = mock(AgentRunService.class);
        InMemoryRunLogEventBus bus = mock(InMemoryRunLogEventBus.class);
        RunLogSubscription subscription = mock(RunLogSubscription.class);
        when(bus.openSubscription(RUN_ID)).thenReturn(subscription);
        when(service.get(RUN_ID)).thenReturn(checkpoint(
                AgentState.empty().withVariable(OpsNode.TIMED_OUT_KEY, "FALSE")));
        RunTerminalController controller = new RunTerminalController(service, bus);

        assertThatThrownBy(() -> controller.logs(RUN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(OpsNode.TIMED_OUT_KEY);
        verify(subscription).close();
    }

    private AgentState terminalState() {
        return AgentState.empty()
                .withVariable(OpsNode.STDOUT_KEY, "\u001b[32mok\u001b[0m")
                .withVariable(OpsNode.STDERR_KEY, "warn\n")
                .withVariable(OpsNode.EXIT_CODE_KEY, "0")
                .withVariable(OpsNode.TIMED_OUT_KEY, "false");
    }

    private RunCheckpoint checkpoint(AgentState state) {
        return new RunCheckpoint(
                RUN_ID,
                3,
                "coder-ops-reviewer",
                RunStatus.RUNNING,
                state,
                "reviewer",
                null,
                null,
                null,
                null,
                OCCURRED_AT);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SSE JSON 解析失败", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean(destroyMethod = "close")
        InMemoryRunLogEventBus runLogEventBus() {
            return new InMemoryRunLogEventBus();
        }
    }
}
