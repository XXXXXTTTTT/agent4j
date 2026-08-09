package com.agent.web.terminal;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.nodes.OpsNode;
import com.agent.core.trace.RunLogEvent;
import com.agent.core.trace.RunLogStream;
import com.agent.web.log.InMemoryRunLogEventBus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

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
        classes = RunTerminalWebSocketTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.group.readiness.include=readinessState")
class RunTerminalWebSocketTest {

    private static final UUID RUN_ID = UUID.fromString(
            "a7b5a9d2-3ce8-4294-b4b8-b34280850f51");
    private static final UUID OTHER_RUN_ID = UUID.fromString(
            "eeb10e63-8e3c-4557-914c-f2845d97e0e3");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-02T03:00:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryRunLogEventBus eventBus;

    @MockBean
    private Checkpointer checkpointer;

    @BeforeEach
    void setUpCheckpoint() {
        when(checkpointer.loadLatest(RUN_ID)).thenReturn(Optional.of(checkpoint()));
    }

    @Test
    void sendsSnapshotThenOnlyLogsForRequestedRunWithAnsiPreserved() {
        RunLogEvent requested = event(
                RUN_ID,
                UUID.fromString("0c29af7f-21fc-4416-a38f-e14b9c8f019f"),
                "\u001b[31mfailed\u001b[0m");
        RunLogEvent other = event(
                OTHER_RUN_ID,
                UUID.fromString("d865b8b8-73ff-4d5d-af2e-015498996a3e"),
                "other");
        List<JsonNode> frames = receiveFrames(RUN_ID, 2, () -> {
            eventBus.publish(other);
            eventBus.publish(requested);
        });

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).path("kind").textValue()).isEqualTo("SNAPSHOT");
        assertThat(frames.get(0).path("terminal").path("runId").textValue())
                .isEqualTo(RUN_ID.toString());
        assertThat(frames.get(0).path("terminal").path("stdout").textValue())
                .isEqualTo("\u001b[32mok\u001b[0m");
        assertThat(frames.get(1).path("kind").textValue()).isEqualTo("LOG");
        assertThat(frames.get(1).path("event").path("runId").textValue())
                .isEqualTo(RUN_ID.toString());
        assertThat(frames.get(1).path("event").path("text").textValue())
                .isEqualTo("\u001b[31mfailed\u001b[0m");
    }

    @Test
    void buffersLogPublishedWhileLoadingSnapshot() {
        RunLogEvent buffered = event(
                RUN_ID,
                UUID.fromString("39ad2f08-0150-4fb5-a9dd-4bff90f9779e"),
                "during-snapshot");
        when(checkpointer.loadLatest(RUN_ID)).thenAnswer(invocation -> {
            eventBus.publish(buffered);
            eventBus.complete(RUN_ID);
            return Optional.of(checkpoint());
        });

        List<JsonNode> frames = receiveUntilServerCloses(RUN_ID);

        assertThat(frames)
                .extracting(frame -> frame.path("kind").textValue())
                .containsExactly("SNAPSHOT", "LOG");
        assertThat(frames.get(1).path("event").path("eventId").textValue())
                .isEqualTo(buffered.eventId().toString());
    }

    @Test
    void terminalCompletionClosesConnectionNormally() {
        RunLogEvent last = event(
                RUN_ID,
                UUID.fromString("da83ff34-df24-427c-a518-a69204e48800"),
                "done");
        List<JsonNode> frames = new CopyOnWriteArrayList<>();
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

        client.execute(uri(RUN_ID), session -> session.receive()
                        .map(message -> readJson(message.getPayloadAsText()))
                        .doOnNext(frame -> {
                            frames.add(frame);
                            if (frames.size() == 1) {
                                eventBus.publish(last);
                                eventBus.complete(RUN_ID);
                            }
                        })
                        .then(session.closeStatus().doOnNext(closeStatus::set))
                        .then())
                .block(Duration.ofSeconds(5));

        assertThat(frames).hasSize(2);
        assertThat(closeStatus.get().getCode()).isEqualTo(CloseStatus.NORMAL.getCode());
    }

    @Test
    void clientDisconnectReleasesSubscriptionForNextConnection() {
        assertThat(receiveFrames(RUN_ID, 1, () -> { })).hasSize(1);
        RunLogEvent log = event(
                RUN_ID,
                UUID.fromString("cf017995-abdf-4523-95ea-18f625b33d94"),
                "second");

        List<JsonNode> secondFrames = receiveFrames(
                RUN_ID, 2, () -> eventBus.publish(log));

        assertThat(secondFrames).hasSize(2);
        assertThat(secondFrames.get(1).path("event").path("eventId").textValue())
                .isEqualTo(log.eventId().toString());
    }

    @Test
    void closesUnknownRunWithApplicationStatus4404() {
        UUID missing = UUID.fromString("4fb5ef72-fdd0-425d-a41a-f7642fc31643");
        when(checkpointer.loadLatest(missing)).thenReturn(Optional.empty());
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

        client.execute(uri(missing), session -> session.receive()
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

    private List<JsonNode> receiveUntilServerCloses(UUID runId) {
        List<JsonNode> frames = new CopyOnWriteArrayList<>();
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        client.execute(uri(runId), session -> session.receive()
                        .map(message -> readJson(message.getPayloadAsText()))
                        .doOnNext(frames::add)
                        .then())
                .block(Duration.ofSeconds(5));
        return List.copyOf(frames);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("终端 WebSocket JSON 解析失败", exception);
        }
    }

    private URI uri(UUID runId) {
        return URI.create("ws://localhost:" + port + "/ws/runs/" + runId + "/terminal");
    }

    private RunLogEvent event(UUID runId, UUID eventId, String text) {
        return new RunLogEvent(
                eventId,
                runId,
                "ops",
                0,
                RunLogStream.PTY,
                text,
                OCCURRED_AT.plusSeconds(1));
    }

    private RunCheckpoint checkpoint() {
        AgentState state = AgentState.empty()
                .withVariable(OpsNode.STDOUT_KEY, "\u001b[32mok\u001b[0m")
                .withVariable(OpsNode.STDERR_KEY, "")
                .withVariable(OpsNode.EXIT_CODE_KEY, "0")
                .withVariable(OpsNode.TIMED_OUT_KEY, "false");
        return new RunCheckpoint(
                RUN_ID,
                2,
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

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @Import(TerminalWebSocketConfiguration.class)
    static class TestApplication {

        @Bean(destroyMethod = "close")
        InMemoryRunLogEventBus runLogEventBus() {
            return new InMemoryRunLogEventBus();
        }

        @Bean
        WebSocketHandlerAdapter webSocketHandlerAdapter() {
            return new WebSocketHandlerAdapter();
        }
    }
}
