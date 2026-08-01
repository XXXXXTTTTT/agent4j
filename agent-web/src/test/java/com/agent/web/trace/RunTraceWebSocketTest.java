package com.agent.web.trace;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.trace.TraceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = RunTraceWebSocketTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunTraceWebSocketTest {

    private static final UUID RUN_ID =
            UUID.fromString("a1b591ca-b62d-42fa-9c81-a487320b2788");
    private static final UUID OTHER_RUN_ID =
            UUID.fromString("aab26726-0535-4055-aa8b-a7e97716f121");
    private static final Instant NOW = Instant.parse("2026-08-01T11:00:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryTraceEventBus eventBus;

    @MockBean
    private Checkpointer checkpointer;

    @BeforeEach
    void setUpCheckpoint() {
        when(checkpointer.loadLatest(RUN_ID)).thenReturn(Optional.of(runningCheckpoint()));
    }

    @Test
    void sendsSnapshotThenOnlyEventsForTheRequestedRun() {
        TraceEvent otherEvent = started(OTHER_RUN_ID, "other");
        TraceEvent requestedEvent = started(RUN_ID, "coder");
        List<JsonNode> frames = receiveFrames(RUN_ID, 2, () -> {
            eventBus.publish(otherEvent);
            eventBus.publish(requestedEvent);
        });

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).path("kind").textValue()).isEqualTo("SNAPSHOT");
        assertThat(frames.get(0).path("run").path("runId").textValue())
                .isEqualTo(RUN_ID.toString());
        assertThat(frames.get(0).path("run").path("status").textValue())
                .isEqualTo("RUNNING");
        assertThat(frames.get(1).path("kind").textValue()).isEqualTo("EVENT");
        assertThat(frames.get(1).path("event").path("type").textValue())
                .isEqualTo("NODE_STARTED");
        assertThat(frames.get(1).path("event").path("runId").textValue())
                .isEqualTo(RUN_ID.toString());
    }

    @Test
    void terminalEventClosesConnectionNormally() {
        TraceEvent terminal = new TraceEvent.Completed(
                UUID.fromString("96d2a822-0876-4ac0-b069-fc65f824de2e"),
                RUN_ID,
                2,
                NOW);
        List<JsonNode> frames = new CopyOnWriteArrayList<>();
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

        client.execute(uri(RUN_ID), session -> session.receive()
                        .map(message -> readJson(message.getPayloadAsText()))
                        .doOnNext(frame -> {
                            frames.add(frame);
                            if (frames.size() == 1) {
                                eventBus.publish(terminal);
                            }
                        })
                        .then(session.closeStatus().doOnNext(closeStatus::set))
                        .then())
                .block(Duration.ofSeconds(5));

        assertThat(frames).hasSize(2);
        assertThat(frames.get(1).path("event").path("type").textValue())
                .isEqualTo("COMPLETED");
        assertThat(closeStatus.get().getCode()).isEqualTo(CloseStatus.NORMAL.getCode());
    }

    @Test
    void buffersEventsPublishedWhileLoadingTheSnapshot() {
        TraceEvent terminal = new TraceEvent.Completed(
                UUID.fromString("cd7bd24f-0a30-4303-bec0-250308e65a33"),
                RUN_ID,
                2,
                NOW);
        when(checkpointer.loadLatest(RUN_ID)).thenAnswer(invocation -> {
            eventBus.publish(terminal);
            return Optional.of(runningCheckpoint());
        });
        List<JsonNode> frames = new CopyOnWriteArrayList<>();
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

        client.execute(uri(RUN_ID), session -> session.receive()
                        .map(message -> readJson(message.getPayloadAsText()))
                        .doOnNext(frames::add)
                        .take(Duration.ofMillis(500))
                        .then(session.close()))
                .block(Duration.ofSeconds(5));

        assertThat(frames)
                .extracting(frame -> frame.path("kind").textValue())
                .containsExactly("SNAPSHOT", "EVENT");
    }

    @Test
    void clientDisconnectRemovesSubscriptionForNextConnection() {
        List<JsonNode> firstFrames = receiveFrames(RUN_ID, 1, () -> { });
        TraceEvent event = started(RUN_ID, "reviewer");
        List<JsonNode> secondFrames = receiveFrames(
                RUN_ID, 2, () -> eventBus.publish(event));

        assertThat(firstFrames).hasSize(1);
        assertThat(secondFrames).hasSize(2);
        assertThat(secondFrames.get(1).path("event").path("nodeName").textValue())
                .isEqualTo("reviewer");
    }

    @Test
    void closesUnknownRunWithApplicationStatus4404() {
        UUID missingRunId = UUID.fromString("1ab78b6c-3c08-4d8f-b7fe-e98be7237bad");
        when(checkpointer.loadLatest(missingRunId)).thenReturn(Optional.empty());
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

        client.execute(uri(missingRunId), session -> session.receive()
                        .then(session.closeStatus().doOnNext(closeStatus::set))
                        .then())
                .block(Duration.ofSeconds(5));

        assertThat(closeStatus.get()).isEqualTo(new CloseStatus(4404, "run not found"));
    }

    private List<JsonNode> receiveFrames(UUID runId, int count, Runnable afterSnapshot) {
        List<JsonNode> frames = new CopyOnWriteArrayList<>();
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        client.execute(uri(runId), session -> session.receive()
                        .map(message -> readJson(message.getPayloadAsText()))
                        .doOnNext(frame -> {
                            frames.add(frame);
                            if (frames.size() == 1) {
                                afterSnapshot.run();
                            }
                        })
                        .take(count)
                        .then(session.close()))
                .block(Duration.ofSeconds(5));
        return List.copyOf(frames);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("WebSocket JSON 解析失败", exception);
        }
    }

    private URI uri(UUID runId) {
        return URI.create("ws://localhost:" + port + "/ws/runs/" + runId + "/trace");
    }

    private TraceEvent started(UUID runId, String nodeName) {
        return new TraceEvent.NodeStarted(UUID.randomUUID(), runId, 1, NOW, nodeName);
    }

    private RunCheckpoint runningCheckpoint() {
        return new RunCheckpoint(
                RUN_ID,
                1,
                "coder-ops-reviewer",
                RunStatus.RUNNING,
                AgentState.empty(),
                "coder",
                null,
                null,
                null,
                null,
                NOW);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @Import(TraceWebSocketConfiguration.class)
    static class TestApplication {

        @Bean(destroyMethod = "close")
        InMemoryTraceEventBus traceEventBus() {
            return new InMemoryTraceEventBus();
        }
    }
}
