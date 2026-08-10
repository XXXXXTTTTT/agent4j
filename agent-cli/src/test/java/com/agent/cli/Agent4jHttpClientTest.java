package com.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Agent4jHttpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void callsExactIdentityAndWorkspacePaths() {
        server.createContext("/api/identity", exchange -> respond(exchange, 200,
                "{\"userId\":\"local\",\"displayName\":\"Local User\"}"));
        server.createContext("/api/workspaces", exchange -> respond(exchange, 200, """
                [{"workspaceId":"deba7286-c149-4d73-83fd-6bc91326ac76",
                  "ownerUserId":"local","displayName":"Agent4J",
                  "workspacePath":"/agent-workspace","repositoryId":"local",
                  "permission":"OWNER","createdAt":"2026-08-10T00:00:00Z",
                  "updatedAt":"2026-08-10T00:00:00Z"}]
                """));
        Agent4jHttpClient client = client();

        assertThat(client.identity().userId()).isEqualTo("local");
        assertThat(client.listWorkspaces()).singleElement()
                .satisfies(workspace -> {
                    assertThat(workspace.workspacePath()).isEqualTo("/agent-workspace");
                    assertThat(workspace.permission()).isEqualTo("OWNER");
                });
    }

    @Test
    void sendsExactWorkspaceConversationAndTurnJsonFields() throws Exception {
        UUID workspaceId = UUID.fromString("deba7286-c149-4d73-83fd-6bc91326ac76");
        UUID conversationId = UUID.fromString("2ee9ea03-6340-4c2a-a510-eb6a7b13def3");
        AtomicReference<JsonNode> workspaceBody = new AtomicReference<>();
        AtomicReference<JsonNode> conversationBody = new AtomicReference<>();
        AtomicReference<JsonNode> turnBody = new AtomicReference<>();
        server.createContext("/api/workspaces", exchange -> {
            workspaceBody.set(readJson(exchange));
            respond(exchange, 201, workspaceJson(workspaceId));
        });
        server.createContext("/api/workspaces/" + workspaceId + "/conversations", exchange -> {
            conversationBody.set(readJson(exchange));
            respond(exchange, 201, conversationJson(conversationId, workspaceId));
        });
        server.createContext("/api/conversations/" + conversationId + "/turns", exchange -> {
            turnBody.set(readJson(exchange));
            respond(exchange, 202, turnJson(conversationId));
        });
        Agent4jHttpClient client = client();

        client.createWorkspace("Agent4J", "/agent-workspace", "local");
        client.createConversation(workspaceId);
        Agent4jClient.Turn turn = client.submitTurn(
                conversationId, "解释当前架构", "");

        assertThat(workspaceBody.get().fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("displayName", "workspacePath", "repositoryId");
        assertThat(workspaceBody.get().path("workspacePath").asText())
                .isEqualTo("/agent-workspace");
        assertThat(conversationBody.get().isObject()).isTrue();
        assertThat(conversationBody.get().size()).isZero();
        assertThat(turnBody.get().fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("content", "reviewerUrl");
        assertThat(turn.runId()).isEqualTo(
                UUID.fromString("cdf4b51e-46fc-4dbd-aa82-06bd555e4226"));
    }

    @Test
    void exposesHttpStatusAndExactProblemBody() {
        server.createContext("/api/identity", exchange -> respond(
                exchange, 409, "{\"title\":\"Conflict\",\"detail\":\"已有活动轮次\"}"));

        assertThatThrownBy(() -> client().identity())
                .isInstanceOf(Agent4jHttpException.class)
                .satisfies(exception -> {
                    Agent4jHttpException failure = (Agent4jHttpException) exception;
                    assertThat(failure.statusCode()).isEqualTo(409);
                    assertThat(failure.responseBody()).contains("已有活动轮次");
                });
    }

    @Test
    void fallsBackToNextEndpointAndPinsSuccessfulEndpoint() throws Exception {
        HttpServer wrongServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer workingServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            wrongServer.createContext("/api/identity", exchange -> respond(
                    exchange, 404, "{\"error\":\"frontend\"}"));
            workingServer.createContext("/api/identity", exchange -> respond(
                    exchange, 200, "{\"userId\":\"local\",\"displayName\":\"Local User\"}"));
            workingServer.createContext("/api/workspaces", exchange -> respond(
                    exchange, 200, "[]"));
            wrongServer.start();
            workingServer.start();

            Agent4jHttpClient client = new Agent4jHttpClient(
                    HttpClient.newHttpClient(),
                    List.of(
                            URI.create("http://127.0.0.1:" + wrongServer.getAddress().getPort()),
                            URI.create("http://127.0.0.1:" + workingServer.getAddress().getPort())),
                    objectMapper);

            assertThat(client.identity().userId()).isEqualTo("local");
            assertThat(client.listWorkspaces()).isEmpty();
        } finally {
            wrongServer.stop(0);
            workingServer.stop(0);
        }
    }

    @Test
    void preservesFirstHttpErrorWhenAllLoopbackEndpointsFail() throws Exception {
        HttpServer conflictServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            conflictServer.createContext("/api/identity", exchange -> respond(
                    exchange, 409, "{\"title\":\"Conflict\",\"detail\":\"已有活动轮次\"}"));
            conflictServer.start();
            URI unavailable = URI.create("http://127.0.0.1:1");
            assertThatThrownBy(() -> new Agent4jHttpClient(
                    HttpClient.newHttpClient(),
                    List.of(
                            URI.create("http://127.0.0.1:" + conflictServer.getAddress().getPort()),
                            unavailable),
                    objectMapper).identity())
                    .isInstanceOf(Agent4jHttpException.class)
                    .satisfies(exception -> {
                        Agent4jHttpException failure = (Agent4jHttpException) exception;
                        assertThat(failure.statusCode()).isEqualTo(409);
                        assertThat(failure.responseBody()).contains("已有活动轮次");
                    });
        } finally {
            conflictServer.stop(0);
        }
    }

    @Test
    void rejectsHttp200WithoutCompleteIdentityFieldsAndUsesNextEndpoint() throws Exception {
        HttpServer wrongServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer workingServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            wrongServer.createContext("/api/identity", exchange -> respond(
                    exchange, 200, "{}"));
            workingServer.createContext("/api/identity", exchange -> respond(
                    exchange, 200, "{\"userId\":\"local\",\"displayName\":\"Local User\"}"));
            wrongServer.start();
            workingServer.start();

            Agent4jHttpClient client = new Agent4jHttpClient(
                    HttpClient.newHttpClient(),
                    List.of(
                            URI.create("http://127.0.0.1:" + wrongServer.getAddress().getPort()),
                            URI.create("http://127.0.0.1:" + workingServer.getAddress().getPort())),
                    objectMapper);

            assertThat(client.identity().displayName()).isEqualTo("Local User");
        } finally {
            wrongServer.stop(0);
            workingServer.stop(0);
        }
    }

    @Test
    void readsTraceAndTerminalSseFromExactRunPaths() {
        UUID runId = UUID.fromString("cdf4b51e-46fc-4dbd-aa82-06bd555e4226");
        server.createContext("/api/runs/" + runId + "/events", exchange -> respondSse(
                exchange, "id: 1\nevent: snapshot\ndata: {\"kind\":\"SNAPSHOT\"}\n\n"));
        server.createContext("/api/runs/" + runId + "/logs", exchange -> respondSse(
                exchange, "id: 2\nevent: log\ndata: {\"kind\":\"LOG\"}\n\n"));
        Agent4jHttpClient client = client();

        List<SseEventReader.SseEvent> trace = client.readTrace(runId);
        List<SseEventReader.SseEvent> logs = client.readLogs(runId);

        assertThat(trace).containsExactly(new SseEventReader.SseEvent(
                "1", "snapshot", "{\"kind\":\"SNAPSHOT\"}"));
        assertThat(logs).containsExactly(new SseEventReader.SseEvent(
                "2", "log", "{\"kind\":\"LOG\"}"));
    }

    private Agent4jHttpClient client() {
        return new Agent4jHttpClient(HttpClient.newHttpClient(), baseUri, objectMapper);
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        return objectMapper.readTree(exchange.getRequestBody());
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respondSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String workspaceJson(UUID workspaceId) {
        return "{\"workspaceId\":\"" + workspaceId
                + "\",\"ownerUserId\":\"local\",\"displayName\":\"Agent4J\""
                + ",\"workspacePath\":\"/agent-workspace\",\"repositoryId\":\"local\""
                + ",\"permission\":\"OWNER\",\"createdAt\":\"2026-08-10T00:00:00Z\""
                + ",\"updatedAt\":\"2026-08-10T00:00:00Z\"}";
    }

    private String conversationJson(UUID conversationId, UUID workspaceId) {
        return "{\"conversationId\":\"" + conversationId
                + "\",\"workspaceId\":\"" + workspaceId
                + "\",\"createdBy\":\"local\",\"title\":\"新会话\",\"status\":\"ACTIVE\""
                + ",\"createdAt\":\"2026-08-10T00:00:00Z\",\"updatedAt\":\"2026-08-10T00:00:00Z\"}";
    }

    private String turnJson(UUID conversationId) {
        return "{\"turnId\":\"b93102ac-59b4-43ee-b312-2ac9b8f353b8\""
                + ",\"conversationId\":\"" + conversationId
                + "\",\"turnIndex\":0,\"userContent\":\"解释当前架构\""
                + ",\"assistantContent\":null"
                + ",\"runId\":\"cdf4b51e-46fc-4dbd-aa82-06bd555e4226\""
                + ",\"status\":\"RUNNING\",\"error\":null"
                + ",\"createdAt\":\"2026-08-10T00:00:00Z\",\"completedAt\":null}";
    }
}
