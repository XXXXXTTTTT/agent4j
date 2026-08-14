package com.agent.web.conversation;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.ChatMessage;
import com.agent.core.cli.CliCommandCatalog;
import com.agent.core.cli.WorkspaceTerminalTargetResolver;
import com.agent.core.tool.DefaultToolRegistry;
import com.agent.core.tool.ToolRegistry;
import com.agent.web.AgentWebApplication;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationFlowIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicInteger EXECUTIONS = new AtomicInteger();
    private static final List<AgentState> INPUTS = new CopyOnWriteArrayList<>();

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
    void preservesTwoTurnContextAcrossApplicationRestart() throws Exception {
        EXECUTIONS.set(0);
        INPUTS.clear();
        UUID conversationId;

        ReactiveWebServerApplicationContext firstApplication = startApplication();
        try {
            WebTestClient client = client(firstApplication);
            String workspaceId = client.get().uri("/api/workspaces")
                    .exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody()
                    .get(0).path("workspaceId").textValue();
            conversationId = UUID.fromString(client.post()
                    .uri("/api/workspaces/{workspaceId}/conversations", workspaceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of())
                    .exchange().expectStatus().isCreated()
                    .expectBody(JsonNode.class).returnResult().getResponseBody()
                    .path("conversationId").textValue());
            submit(client, conversationId, "第一轮问题");
            JsonNode turns = awaitCompletedTurns(client, conversationId, 1);
            assertThat(turns.get(0).path("assistantContent").textValue())
                    .isEqualTo("第一轮回答");
        } finally {
            firstApplication.close();
        }

        ReactiveWebServerApplicationContext secondApplication = startApplication();
        try {
            WebTestClient client = client(secondApplication);
            JsonNode persisted = client.get()
                    .uri("/api/conversations/{conversationId}/turns", conversationId)
                    .exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            assertThat(persisted).hasSize(1);

            submit(client, conversationId, "第二轮问题");
            JsonNode turns = awaitCompletedTurns(client, conversationId, 2);
            assertThat(turns.get(1).path("assistantContent").textValue())
                    .isEqualTo("第二轮回答");
            assertThat(INPUTS).hasSize(2);
            assertThat(INPUTS.get(1).messages()).containsExactly(
                    ChatMessage.user("第一轮问题"),
                    ChatMessage.assistant("第一轮回答"));
        } finally {
            secondApplication.close();
        }
    }

    private ReactiveWebServerApplicationContext startApplication() {
        SpringApplication application = new SpringApplication(
                AgentWebApplication.class, ConversationGraphConfiguration.class);
        application.setRegisterShutdownHook(false);
        return (ReactiveWebServerApplicationContext) application.run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--agent.production.enabled=true",
                "--agent.production.workspace=" + Path.of(".").toAbsolutePath().normalize(),
                "--agent.production.repository-id=conversation-flow",
                "--agent.production.user-id=conversation-user");
    }

    private WebTestClient client(ReactiveWebServerApplicationContext application) {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + application.getWebServer().getPort())
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private void submit(WebTestClient client, UUID conversationId, String content) {
        client.post().uri("/api/conversations/{conversationId}/turns", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("content", content))
                .exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.runId").isNotEmpty();
    }

    private JsonNode awaitCompletedTurns(
            WebTestClient client,
            UUID conversationId,
            int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            latest = client.get()
                    .uri("/api/conversations/{conversationId}/turns", conversationId)
                    .exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            if (latest.size() == expectedCount
                    && "COMPLETED".equals(latest.get(expectedCount - 1)
                    .path("status").textValue())) {
                return latest;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待会话轮次完成超时: " + latest);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConversationGraphConfiguration {

        @Bean("code-agent")
        GraphFactory conversationGraph() {
            return () -> new StateGraph(1)
                    .addNode("planner", state -> {
                        INPUTS.add(state);
                        int execution = EXECUTIONS.incrementAndGet();
                        return state.withVariable(
                                        "final_response",
                                        execution == 1 ? "第一轮回答" : "第二轮回答")
                                .withTraceEntry("planner");
                    })
                    .setEntryPoint("planner")
                    .addEdge("planner", StateGraph.END);
        }

        @Bean(destroyMethod = "close")
        ToolRegistry testToolRegistry() {
            return new DefaultToolRegistry();
        }

        @Bean
        CliCommandCatalog testCliCommandCatalog() {
            return new CliCommandCatalog(List.of());
        }

        @Bean
        WorkspaceTerminalTargetResolver testWorkspaceTerminalTargetResolver() {
            return ignored -> {
                throw new IllegalStateException("会话连续性测试不执行 CLI 命令");
            };
        }
    }
}
