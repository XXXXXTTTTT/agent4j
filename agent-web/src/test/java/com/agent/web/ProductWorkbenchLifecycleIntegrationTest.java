package com.agent.web;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.Node;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.engine.StateGraph;
import com.agent.core.nodes.OpsNode;
import com.agent.core.trace.RunLogEvent;
import com.agent.core.trace.RunLogPublisher;
import com.agent.core.trace.RunLogStream;
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
import java.time.Instant;
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

class ProductWorkbenchLifecycleIntegrationTest {

    private static final String GRAPH_ID = "product-workbench-flow";
    private static final String ORIGINAL_COMMAND = "mvn test";
    private static final String UPDATED_COMMAND = "mvn verify";
    private static final String ANSI_LOG = "\u001b[32mtests passed\u001b[0m\r\n";
    private static final UUID INTERRUPT_ID =
            UUID.fromString("34ddd939-35cb-46f1-ac02-eec0bc9c24f8");
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
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
    void completesRestTerminalApprovalAndHistoryLifecycle() throws Exception {
        prepareEntered = new CountDownLatch(1);
        releasePrepare = new CountDownLatch(1);
        OPS_EXECUTIONS.set(0);

        ReactiveWebServerApplicationContext context = startApplication();
        CompletableFuture<Void> terminalConnection = null;
        try {
            WebTestClient webClient = WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + context.getWebServer().getPort())
                    .responseTimeout(Duration.ofSeconds(10))
                    .build();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            JsonNode created = createRun(webClient);
            UUID runId = UUID.fromString(created.path("runId").textValue());
            assertThat(prepareEntered.await(5, TimeUnit.SECONDS)).isTrue();

            List<JsonNode> terminalFrames = new CopyOnWriteArrayList<>();
            CountDownLatch snapshotReceived = new CountDownLatch(1);
            CountDownLatch logReceived = new CountDownLatch(1);
            terminalConnection = receiveTerminal(
                    context,
                    objectMapper,
                    runId,
                    terminalFrames,
                    snapshotReceived,
                    logReceived);

            assertThat(snapshotReceived.await(5, TimeUnit.SECONDS)).isTrue();
            releasePrepare.countDown();
            JsonNode waiting = awaitStatus(webClient, runId, "WAITING_APPROVAL");
            assertThat(waiting.path("interruptRequest").path("details")
                    .path(OpsNode.COMMAND_KEY).textValue()).isEqualTo(ORIGINAL_COMMAND);

            JsonNode approved = approveRun(
                    webClient, runId, waiting.path("version").longValue());
            assertThat(approved.path("status").textValue()).isEqualTo("RUNNING");
            assertThat(approved.path("state").path("variables")
                    .path(OpsNode.COMMAND_KEY).textValue()).isEqualTo(UPDATED_COMMAND);

            assertThat(logReceived.await(5, TimeUnit.SECONDS)).isTrue();
            terminalConnection.get(5, TimeUnit.SECONDS);
            JsonNode completed = awaitStatus(webClient, runId, "COMPLETED");
            JsonNode history = getHistory(webClient, runId);

            assertThat(terminalFrames)
                    .extracting(frame -> frame.path("kind").textValue())
                    .containsExactly("SNAPSHOT", "LOG");
            assertThat(terminalFrames.get(1).path("event").path("text").textValue())
                    .isEqualTo(ANSI_LOG);
            assertThat(completed.path("state").path("variables")
                    .path(OpsNode.COMMAND_KEY).textValue()).isEqualTo(UPDATED_COMMAND);
            assertThat(completed.path("state").path("variables")
                    .path(OpsNode.STDOUT_KEY).textValue()).isEqualTo(ANSI_LOG);
            assertThat(history).hasSize(5);
            assertThat(history.findValues("version"))
                    .extracting(JsonNode::longValue)
                    .containsExactly(0L, 1L, 2L, 3L, 4L);
            assertThat(history.get(2).path("state").path("variables")
                    .path(OpsNode.COMMAND_KEY).textValue()).isEqualTo(ORIGINAL_COMMAND);
            assertThat(history.get(3).path("state").path("variables")
                    .path(OpsNode.COMMAND_KEY).textValue()).isEqualTo(UPDATED_COMMAND);
            assertThat(history.get(4).path("state").path("variables")
                    .path(OpsNode.COMMAND_KEY).textValue()).isEqualTo(UPDATED_COMMAND);
            assertThat(OPS_EXECUTIONS).hasValue(1);
        } finally {
            if (terminalConnection != null && !terminalConnection.isDone()) {
                terminalConnection.cancel(true);
            }
            releasePrepare.countDown();
            context.close();
        }
    }

    private ReactiveWebServerApplicationContext startApplication() {
        SpringApplication application = new SpringApplication(
                AgentWebApplication.class,
                ProductWorkbenchFlowConfiguration.class);
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
                        "graphId", GRAPH_ID,
                        "initialState", Map.of(
                                "messages", List.of(),
                                "variables", Map.of(OpsNode.COMMAND_KEY, ORIGINAL_COMMAND),
                                "trace", List.of())))
                .exchange()
                .expectStatus().isAccepted()
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
                        "reason", "已确认测试命令",
                        "variableUpdates", Map.of(
                                OpsNode.COMMAND_KEY, UPDATED_COMMAND)))
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

    private JsonNode getHistory(WebTestClient webClient, UUID runId) {
        return webClient.get()
                .uri("/api/runs/{runId}/history", runId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode awaitStatus(WebTestClient webClient, UUID runId, String status)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            latest = getRun(webClient, runId);
            if (status.equals(latest.path("status").textValue())) {
                return latest;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待 Run 状态超时: expected=" + status
                + ", actual=" + (latest == null ? null : latest.path("status").textValue()));
    }

    private CompletableFuture<Void> receiveTerminal(
            ReactiveWebServerApplicationContext context,
            ObjectMapper objectMapper,
            UUID runId,
            List<JsonNode> frames,
            CountDownLatch snapshotReceived,
            CountDownLatch logReceived) {
        URI uri = URI.create("ws://localhost:"
                + context.getWebServer().getPort()
                + "/ws/runs/"
                + runId
                + "/terminal");
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        return client.execute(uri, session -> session.receive()
                        .map(message -> readJson(
                                objectMapper, message.getPayloadAsText()))
                        .doOnNext(frame -> {
                            frames.add(frame);
                            if ("SNAPSHOT".equals(frame.path("kind").textValue())) {
                                snapshotReceived.countDown();
                            }
                            if ("LOG".equals(frame.path("kind").textValue())) {
                                logReceived.countDown();
                            }
                        })
                        .then())
                .toFuture();
    }

    private JsonNode readJson(ObjectMapper objectMapper, String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("终端集成测试 JSON 解析失败", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProductWorkbenchFlowConfiguration {

        @Bean(GRAPH_ID)
        GraphFactory productWorkbenchFlow(RunLogPublisher logPublisher) {
            return () -> {
                InterruptPolicy interruptPolicy = (runId, nodeName, state) ->
                        "ops".equals(nodeName)
                                ? Optional.of(new InterruptRequest(
                                        INTERRUPT_ID,
                                        "ops",
                                        "测试命令需要审批",
                                        Map.of(
                                                OpsNode.COMMAND_KEY,
                                                state.variables().get(
                                                        OpsNode.COMMAND_KEY))))
                                : Optional.empty();
                return new StateGraph(2, interruptPolicy)
                        .addNode("prepare", state -> {
                            prepareEntered.countDown();
                            if (!releasePrepare.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("等待终端订阅超时");
                            }
                            return state.withTraceEntry("prepare");
                        })
                        .addNode("ops", new Node() {
                            @Override
                            public AgentState execute(AgentState state) {
                                throw new AssertionError("不应调用无上下文入口");
                            }

                            @Override
                            public AgentState execute(
                                    NodeExecutionContext executionContext,
                                    AgentState state) {
                                OPS_EXECUTIONS.incrementAndGet();
                                logPublisher.publish(new RunLogEvent(
                                        UUID.randomUUID(),
                                        executionContext.runId(),
                                        executionContext.nodeName(),
                                        0,
                                        RunLogStream.PTY,
                                        ANSI_LOG,
                                        Instant.now()));
                                return state
                                        .withVariable(OpsNode.STDOUT_KEY, ANSI_LOG)
                                        .withVariable(OpsNode.STDERR_KEY, "")
                                        .withVariable(OpsNode.EXIT_CODE_KEY, "0")
                                        .withVariable(OpsNode.TIMED_OUT_KEY, "false")
                                        .withTraceEntry("ops");
                            }
                        })
                        .setEntryPoint("prepare")
                        .addEdge("prepare", "ops")
                        .addEdge("ops", StateGraph.END);
            };
        }
    }
}
