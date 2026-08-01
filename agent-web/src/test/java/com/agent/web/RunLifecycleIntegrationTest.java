package com.agent.web;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;
import com.agent.core.engine.StateGraph;
import com.agent.web.controller.RunView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunLifecycleIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final UUID INTERRUPT_ID =
            UUID.fromString("fbf670d7-6a3a-4833-a61b-a48899944ab0");
    private static final AtomicInteger OPS_EXECUTIONS = new AtomicInteger();

    private static volatile CountDownLatch prepareEntered;
    private static volatile CountDownLatch releasePrepare;

    @BeforeAll
    static void startPostgres() {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "Docker Engine 不可用: " + exception.getMessage());
            return;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker Engine 不可用");
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    void runsRestWebSocketAndPostgresApprovalLifecycle() throws Exception {
        prepareEntered = new CountDownLatch(1);
        releasePrepare = new CountDownLatch(1);
        OPS_EXECUTIONS.set(0);

        ReactiveWebServerApplicationContext context = startApplication();
        CompletableFuture<Void> webSocket = null;
        try {
            WebTestClient webClient = WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + context.getWebServer().getPort())
                    .responseTimeout(Duration.ofSeconds(10))
                    .build();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            Checkpointer checkpointer = context.getBean(Checkpointer.class);
            JsonNode created = createRun(webClient);
            UUID runId = UUID.fromString(created.path("runId").textValue());
            assertThat(prepareEntered.await(5, TimeUnit.SECONDS)).isTrue();

            List<JsonNode> frames = new CopyOnWriteArrayList<>();
            List<String> eventTypes = new CopyOnWriteArrayList<>();
            CountDownLatch snapshotReceived = new CountDownLatch(1);
            CountDownLatch interruptedReceived = new CountDownLatch(1);
            CountDownLatch completedReceived = new CountDownLatch(1);
            webSocket = receiveTrace(
                    context,
                    objectMapper,
                    runId,
                    frames,
                    eventTypes,
                    snapshotReceived,
                    interruptedReceived,
                    completedReceived);

            assertThat(snapshotReceived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(frames.getFirst().path("kind").textValue()).isEqualTo("SNAPSHOT");
            assertThat(frames.getFirst().path("run")).isEqualTo(created);
            releasePrepare.countDown();

            assertThat(interruptedReceived.await(5, TimeUnit.SECONDS)).isTrue();
            JsonNode waiting = getRun(webClient, runId);
            assertThat(waiting.path("status").textValue()).isEqualTo("WAITING_APPROVAL");
            assertThat(waiting.path("nextNode").textValue()).isEqualTo("ops");
            assertThat(waiting.path("interruptRequest").path("nodeName").textValue())
                    .isEqualTo("ops");
            assertThat(objectMapper.treeToValue(waiting, RunView.class))
                    .isEqualTo(RunView.from(checkpointer.loadLatest(runId).orElseThrow()));

            long waitingVersion = waiting.path("version").longValue();
            JsonNode approved = approveRun(webClient, runId, waitingVersion);
            assertThat(approved.path("status").textValue()).isEqualTo("RUNNING");
            assertThat(approved.path("approvalDecision").textValue()).isEqualTo("APPROVE");
            assertThat(approved.path("approvalReason").textValue()).isEqualTo("已核对危险操作");

            assertThat(completedReceived.await(5, TimeUnit.SECONDS)).isTrue();
            webSocket.get(5, TimeUnit.SECONDS);
            JsonNode completed = getRun(webClient, runId);
            RunCheckpoint latest = checkpointer.loadLatest(runId).orElseThrow();

            assertThat(completed.path("status").textValue()).isEqualTo("COMPLETED");
            assertThat(completed.path("state").path("trace"))
                    .containsExactly(
                            objectMapper.getNodeFactory().textNode("prepare"),
                            objectMapper.getNodeFactory().textNode("ops"));
            assertThat(objectMapper.treeToValue(completed, RunView.class))
                    .isEqualTo(RunView.from(latest));
            assertThat(OPS_EXECUTIONS).hasValue(1);
            assertThat(eventTypes)
                    .containsSubsequence("INTERRUPTED", "APPROVED", "COMPLETED");
            assertThat(checkpointer.loadHistory(runId))
                    .extracting(RunCheckpoint::version)
                    .containsExactly(0L, 1L, 2L, 3L, 4L);
            assertThat(latest.status()).isEqualTo(RunStatus.COMPLETED);
        } finally {
            if (webSocket != null && !webSocket.isDone()) {
                webSocket.cancel(true);
            }
            releasePrepare.countDown();
            context.close();
        }
    }

    private ReactiveWebServerApplicationContext startApplication() {
        SpringApplication application = new SpringApplication(
                AgentWebApplication.class,
                ApprovalFlowConfiguration.class);
        application.setRegisterShutdownHook(false);
        return (ReactiveWebServerApplicationContext) application.run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword());
    }

    private JsonNode createRun(WebTestClient webClient) {
        return webClient.post()
                .uri("/api/runs")
                .bodyValue(Map.of(
                        "graphId", "approval-flow",
                        "initialState", Map.of(
                                "messages", List.of(),
                                "variables", Map.of(),
                                "trace", List.of())))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode getRun(WebTestClient webClient, UUID runId) {
        return webClient.get()
                .uri("/api/runs/{runId}", runId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode approveRun(WebTestClient webClient, UUID runId, long version) {
        return webClient.post()
                .uri("/api/runs/{runId}/approval", runId)
                .bodyValue(Map.of(
                        "decision", "APPROVE",
                        "expectedVersion", version,
                        "reason", "已核对危险操作"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private CompletableFuture<Void> receiveTrace(
            ReactiveWebServerApplicationContext context,
            ObjectMapper objectMapper,
            UUID runId,
            List<JsonNode> frames,
            List<String> eventTypes,
            CountDownLatch snapshotReceived,
            CountDownLatch interruptedReceived,
            CountDownLatch completedReceived) {
        URI uri = URI.create("ws://localhost:"
                + context.getWebServer().getPort()
                + "/ws/runs/"
                + runId
                + "/trace");
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        return client.execute(uri, session -> session.receive()
                        .map(message -> readJson(objectMapper, message.getPayloadAsText()))
                        .doOnNext(frame -> captureFrame(
                                frame,
                                frames,
                                eventTypes,
                                snapshotReceived,
                                interruptedReceived,
                                completedReceived))
                        .then())
                .toFuture();
    }

    private void captureFrame(
            JsonNode frame,
            List<JsonNode> frames,
            List<String> eventTypes,
            CountDownLatch snapshotReceived,
            CountDownLatch interruptedReceived,
            CountDownLatch completedReceived) {
        frames.add(frame);
        if ("SNAPSHOT".equals(frame.path("kind").textValue())) {
            snapshotReceived.countDown();
            return;
        }
        String type = frame.path("event").path("type").textValue();
        eventTypes.add(type);
        if ("INTERRUPTED".equals(type)) {
            interruptedReceived.countDown();
        }
        if ("COMPLETED".equals(type)) {
            completedReceived.countDown();
        }
    }

    private JsonNode readJson(ObjectMapper objectMapper, String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("集成测试 WebSocket JSON 解析失败", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ApprovalFlowConfiguration {

        @Bean("approval-flow")
        GraphFactory approvalFlow() {
            return () -> {
                InterruptPolicy policy = (runId, nodeName, state) ->
                        "ops".equals(nodeName)
                                ? Optional.of(new InterruptRequest(
                                        INTERRUPT_ID,
                                        "ops",
                                        "危险操作需要审批",
                                        Map.of("command", "mvn verify")))
                                : Optional.empty();
                return new StateGraph(2, policy)
                        .addNode("prepare", state -> {
                            prepareEntered.countDown();
                            if (!releasePrepare.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("等待 WebSocket 订阅超时");
                            }
                            return state.withTraceEntry("prepare");
                        })
                        .addNode("ops", state -> {
                            OPS_EXECUTIONS.incrementAndGet();
                            return state.withTraceEntry("ops");
                        })
                        .setEntryPoint("prepare")
                        .addEdge("prepare", "ops")
                        .addEdge("ops", StateGraph.END);
            };
        }
    }
}
