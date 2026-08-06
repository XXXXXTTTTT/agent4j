package com.agent.web.trace;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.AgentState;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.trace.TraceEvent;
import com.agent.web.controller.RunExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = RunTraceController.class)
@Import({RunExceptionHandler.class, RunTraceController.class})
class RunTraceControllerTest {

    private static final UUID RUN_ID = UUID.fromString(
            "6194c1c1-42a5-4e97-a578-f3934128a391");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-06T01:00:00Z");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryTraceEventBus eventBus;

    @MockBean
    private AgentRunService runService;

    @MockBean
    private Checkpointer checkpointer;

    @Test
    void sendsAuthoritativeSnapshotThenBufferedProgressEvent() {
        TraceEvent progress = new TraceEvent.NodeProgress(
                UUID.fromString("43f13a90-b5f4-4bd4-827e-a181a3596077"),
                RUN_ID,
                2,
                OCCURRED_AT.plusSeconds(1),
                "planner",
                "正在识别任务意图");
        TraceEvent completed = new TraceEvent.Completed(
                UUID.fromString("24fd28c6-276f-4fb4-9b0b-88cde23d9cff"),
                RUN_ID,
                3,
                OCCURRED_AT.plusSeconds(2));
        when(runService.get(RUN_ID)).thenAnswer(invocation -> {
            eventBus.publish(progress);
            eventBus.publish(completed);
            return checkpoint();
        });

        byte[] responseBody = webTestClient.get()
                .uri("/api/runs/{runId}/events", RUN_ID)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody()
                .returnResult()
                .getResponseBody();

        String body = new String(responseBody, StandardCharsets.UTF_8);
        assertThat(body.lines().filter(line -> line.startsWith("event:")))
                .containsExactly("event:snapshot", "event:trace", "event:trace");
        assertThat(body.lines().filter(line -> line.startsWith("id:")))
                .containsExactly(
                        "id:2",
                        "id:" + progress.eventId(),
                        "id:" + completed.eventId());
        List<JsonNode> frames = body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> readJson(line.substring("data:".length())))
                .toList();
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).path("kind").textValue()).isEqualTo("SNAPSHOT");
        assertThat(frames.get(0).path("run").path("runId").textValue())
                .isEqualTo(RUN_ID.toString());
        assertThat(frames.get(1).path("kind").textValue()).isEqualTo("EVENT");
        assertThat(frames.get(1).path("event").path("type").textValue())
                .isEqualTo("NODE_PROGRESS");
        assertThat(frames.get(1).path("event").path("summary").textValue())
                .isEqualTo("正在识别任务意图");
    }

    private RunCheckpoint checkpoint() {
        return new RunCheckpoint(
                RUN_ID,
                2,
                "code-agent",
                RunStatus.RUNNING,
                AgentState.empty(),
                "planner",
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
            throw new IllegalStateException("Trace SSE JSON 解析失败", exception);
        }
    }
}
