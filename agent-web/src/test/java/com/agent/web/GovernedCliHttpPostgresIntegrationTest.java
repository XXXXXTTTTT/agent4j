package com.agent.web;

import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.CliCommandDefinition;
import com.agent.core.cli.CliRiskLevel;
import com.agent.core.intent.RequiredCapability;
import com.agent.core.trace.RunLogPublisher;
import com.agent.web.log.InMemoryRunLogEventBus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证受治理 CLI 从 HTTP 到真实 Docker 终端的 PostgreSQL 生命周期。 */
class GovernedCliHttpPostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

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
    void completesReadOnlyAndApprovedMutatingCommandsAndRejectsAnotherMutatingRun(
            @TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("evidence.txt"),
                "read-only evidence\n" + "x".repeat(131_072));
        ReactiveWebServerApplicationContext context = startApplication(workspace);
        try {
            WebTestClient client = webClient(context);
            UUID workspaceId = bootstrapWorkspaceId(client, workspace);
            assertThat(context.getBean(RunLogPublisher.class))
                    .isSameAs(context.getBean(InMemoryRunLogEventBus.class));

            JsonNode readOnly = start(client, workspaceId, "test.cat", List.of("evidence.txt"));
            UUID readOnlyRunId = UUID.fromString(readOnly.path("runId").textValue());
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            List<JsonNode> readOnlyLogFrames = new CopyOnWriteArrayList<>();
            CountDownLatch readOnlyLogSnapshot = new CountDownLatch(1);
            CompletableFuture<Void> readOnlyLogs = subscribeSse(
                    context, objectMapper, "/api/runs/" + readOnlyRunId + "/logs",
                    readOnlyLogFrames, readOnlyLogSnapshot);
            assertThat(readOnlyLogSnapshot.await(10, TimeUnit.SECONDS)).isTrue();
            JsonNode readOnlyCompleted = awaitStatus(client, readOnlyRunId, "COMPLETED");
            readOnlyLogs.get(30, TimeUnit.SECONDS);
            assertThat(readOnlyCompleted.path("graphId").textValue()).isEqualTo("governed-cli");
            assertThat(readOnlyCompleted.path("state").path("variables")
                    .path("ops.stdout").textValue()).contains("read-only evidence");
            assertThat(readOnlyLogFrames).extracting(frame -> frame.path("kind").textValue())
                    .contains("SNAPSHOT", "LOG");
            assertThat(readOnlyLogFrames).filteredOn(frame -> "LOG".equals(
                            frame.path("kind").textValue()))
                    .allSatisfy(frame -> assertThat(frame.path("event").path("runId").textValue())
                            .isEqualTo(readOnlyRunId.toString()));

            JsonNode pendingApproval = start(client, workspaceId, "test.touch", List.of("approved.txt"));
            UUID approvedRunId = UUID.fromString(pendingApproval.path("runId").textValue());
            JsonNode waiting = awaitStatus(client, approvedRunId, "WAITING_APPROVAL");
            assertThat(waiting.path("interruptRequest").path("details")
                    .path("riskLevel").textValue()).isEqualTo("MUTATING");
            assertThat(Files.exists(workspace.resolve("approved.txt"))).isFalse();

            List<JsonNode> traceFrames = new CopyOnWriteArrayList<>();
            List<JsonNode> logFrames = new CopyOnWriteArrayList<>();
            CountDownLatch traceSnapshot = new CountDownLatch(1);
            CountDownLatch logSnapshot = new CountDownLatch(1);
            CompletableFuture<Void> trace = subscribeSse(
                    context, objectMapper, "/api/runs/" + approvedRunId + "/events",
                    traceFrames, traceSnapshot);
            CompletableFuture<Void> logs = subscribeSse(
                    context, objectMapper, "/api/runs/" + approvedRunId + "/logs",
                    logFrames, logSnapshot);
            assertThat(traceSnapshot.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(logSnapshot.await(10, TimeUnit.SECONDS)).isTrue();
            assertSnapshotRun(traceFrames, approvedRunId);
            assertThat(logFrames.getFirst().path("terminal").path("runId").textValue())
                    .isEqualTo(approvedRunId.toString());

            JsonNode approved = decide(
                    client, approvedRunId, waiting.path("version").longValue(), "APPROVE");
            assertThat(approved.path("status").textValue()).isEqualTo("RUNNING");
            assertThat(approved.path("approvalDecision").textValue()).isEqualTo("APPROVE");
            JsonNode approvedCompleted = awaitStatus(client, approvedRunId, "COMPLETED");
            trace.get(30, TimeUnit.SECONDS);
            logs.get(30, TimeUnit.SECONDS);
            assertThat(Files.exists(workspace.resolve("approved.txt"))).isTrue();
            assertThat(traceFrames).extracting(this::traceType)
                    .containsSubsequence("APPROVED", "COMPLETED");
            assertThat(logFrames).extracting(frame -> frame.path("kind").textValue())
                    .containsOnly("SNAPSHOT");

            JsonNode pendingRejection = start(client, workspaceId, "test.touch", List.of("rejected.txt"));
            UUID rejectedRunId = UUID.fromString(pendingRejection.path("runId").textValue());
            JsonNode rejectionWaiting = awaitStatus(client, rejectedRunId, "WAITING_APPROVAL");
            List<JsonNode> rejectionTraceFrames = new CopyOnWriteArrayList<>();
            CountDownLatch rejectionSnapshot = new CountDownLatch(1);
            CompletableFuture<Void> rejectionTrace = subscribeSse(
                    context, objectMapper, "/api/runs/" + rejectedRunId + "/events",
                    rejectionTraceFrames, rejectionSnapshot);
            assertThat(rejectionSnapshot.await(10, TimeUnit.SECONDS)).isTrue();
            assertSnapshotRun(rejectionTraceFrames, rejectedRunId);

            JsonNode rejected = decide(
                    client, rejectedRunId, rejectionWaiting.path("version").longValue(), "REJECT");
            assertThat(rejected.path("status").textValue()).isEqualTo("REJECTED");
            rejectionTrace.get(10, TimeUnit.SECONDS);
            assertThat(Files.exists(workspace.resolve("rejected.txt"))).isFalse();
            assertThat(rejectionTraceFrames).extracting(this::traceType).contains("REJECTED");
        } finally {
            context.close();
        }
    }

    private ReactiveWebServerApplicationContext startApplication(Path workspace) {
        SpringApplication application = new SpringApplication(
                AgentWebApplication.class, GovernedCliTestConfiguration.class);
        application.setRegisterShutdownHook(false);
        return (ReactiveWebServerApplicationContext) application.run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--agent.sample.enabled=false",
                "--agent.rag.enabled=false",
                "--agent.production.enabled=true",
                "--agent.production.workspace=" + workspace,
                "--agent.production.repository-id=governed-cli-http-" + UUID.randomUUID(),
                "--agent.production.user-id=governed-cli-http-user",
                "--agent.production.execution-mode=DOCKER",
                "--agent.production.docker-image=maven:3.9.9-eclipse-temurin-21",
                "--agent.production.container-workspace=/workspace",
                "--agent.llm.enabled=true",
                "--agent.llm.base-url=https://example.invalid",
                "--agent.llm.api-key=test-key",
                "--agent.llm.code-model=test-model",
                "--agent.llm.vision-model=test-model",
                "--agent.llm.image-model=test-model",
                "--agent.llm.quick-classification-model=test-model",
                "--agent.llm.fallback-model=test-model");
    }

    private WebTestClient webClient(ReactiveWebServerApplicationContext context) {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + context.getWebServer().getPort())
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private UUID bootstrapWorkspaceId(WebTestClient client, Path workspace) {
        JsonNode[] workspaces = client.get().uri("/api/workspaces")
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode[].class)
                .returnResult()
                .getResponseBody();
        assertThat(workspaces).isNotNull();
        return java.util.Arrays.stream(workspaces)
                .filter(value -> workspace.toString().replace('\\', '/')
                        .equals(value.path("workspacePath").textValue()))
                .map(value -> UUID.fromString(value.path("workspaceId").textValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到启动器创建的测试工作区"));
    }

    private JsonNode start(
            WebTestClient client,
            UUID workspaceId,
            String commandName,
            List<String> arguments) {
        return client.post()
                .uri("/api/workspaces/{workspaceId}/cli/runs", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "commandName", commandName,
                        "arguments", arguments,
                        "timeoutSeconds", 60))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode decide(WebTestClient client, UUID runId, long expectedVersion, String decision) {
        return client.post()
                .uri("/api/runs/{runId}/approval", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "decision", decision,
                        "expectedVersion", expectedVersion,
                        "reason", "HTTP 集成测试审批"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode awaitStatus(WebTestClient client, UUID runId, String expectedStatus)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            latest = client.get().uri("/api/runs/{runId}", runId)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JsonNode.class)
                    .returnResult()
                    .getResponseBody();
            if (expectedStatus.equals(latest.path("status").textValue())) {
                return latest;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Run 未在限定时间进入 " + expectedStatus + ": " + latest);
    }

    private CompletableFuture<Void> subscribeSse(
            ReactiveWebServerApplicationContext context,
            ObjectMapper objectMapper,
            String path,
            List<JsonNode> frames,
            CountDownLatch snapshot) {
        return WebClient.builder()
                .baseUrl("http://localhost:" + context.getWebServer().getPort())
                .build()
                .get()
                .uri(path)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(value -> {
                    JsonNode frame = readJson(objectMapper, value);
                    frames.add(frame);
                    if ("SNAPSHOT".equals(frame.path("kind").textValue())) {
                        snapshot.countDown();
                    }
                })
                .then()
                .toFuture();
    }

    private JsonNode readJson(ObjectMapper objectMapper, String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SSE JSON 解析失败", exception);
        }
    }

    private void assertSnapshotRun(List<JsonNode> frames, UUID runId) {
        assertThat(frames).isNotEmpty();
        assertThat(frames.getFirst().path("kind").textValue()).isEqualTo("SNAPSHOT");
        assertThat(frames.getFirst().path("run").path("runId").textValue())
                .isEqualTo(runId.toString());
    }

    private String traceType(JsonNode frame) {
        return frame.path("event").path("type").textValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GovernedCliTestConfiguration {

        @Bean
        @Primary
        CliCommandCatalog governedCliHttpTestCatalog() {
            return new CliCommandCatalog(List.of(
                    new CliCommandDefinition(
                            "test.cat",
                            "cat",
                            List.of(),
                            CliRiskLevel.READ_ONLY,
                            java.util.Set.of(RequiredCapability.TERMINAL)),
                    new CliCommandDefinition(
                            "test.touch",
                            "touch",
                            List.of(),
                            CliRiskLevel.MUTATING,
                            java.util.Set.of(RequiredCapability.TERMINAL))));
        }
    }
}
