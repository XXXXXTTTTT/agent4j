package com.agent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 使用 Java 21 HttpClient 调用 Agent4J REST 与 SSE API。 */
public final class Agent4jHttpClient implements Agent4jClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SSE_TIMEOUT = Duration.ofHours(12);

    private final HttpClient httpClient;
    private final URI server;
    private final ObjectMapper objectMapper;

    public Agent4jHttpClient(HttpClient httpClient, URI server, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient 不能为空");
        this.server = Objects.requireNonNull(server, "server 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public Actor identity() {
        return read("GET", "/api/identity", null, Agent4jClient.Actor.class);
    }

    @Override
    public List<Workspace> listWorkspaces() {
        return readList("GET", "/api/workspaces", null, Agent4jClient.Workspace.class);
    }

    @Override
    public Workspace createWorkspace(
            String displayName,
            String workspacePath,
            String repositoryId) {
        return read("POST", "/api/workspaces", objectNode()
                .put("displayName", displayName)
                .put("workspacePath", workspacePath)
                .put("repositoryId", repositoryId), Agent4jClient.Workspace.class);
    }

    @Override
    public List<Conversation> listConversations(UUID workspaceId) {
        return readList("GET", "/api/workspaces/" + workspaceId + "/conversations", null,
                Agent4jClient.Conversation.class);
    }

    @Override
    public Conversation createConversation(UUID workspaceId) {
        return read("POST", "/api/workspaces/" + workspaceId + "/conversations",
                objectMapper.createObjectNode(), Agent4jClient.Conversation.class);
    }

    @Override
    public List<Turn> listTurns(UUID conversationId) {
        return readList("GET", "/api/conversations/" + conversationId + "/turns", null,
                Agent4jClient.Turn.class);
    }

    @Override
    public Turn submitTurn(UUID conversationId, String content, String reviewerUrl) {
        return read("POST", "/api/conversations/" + conversationId + "/turns", objectNode()
                .put("content", content)
                .put("reviewerUrl", reviewerUrl == null ? "" : reviewerUrl),
                Agent4jClient.Turn.class);
    }

    @Override
    public Run getRun(UUID runId) {
        return read("GET", "/api/runs/" + runId, null, Agent4jClient.Run.class);
    }

    @Override
    public List<SseEventReader.SseEvent> readTrace(UUID runId) {
        return readSse("/api/runs/" + runId + "/events");
    }

    @Override
    public List<SseEventReader.SseEvent> readLogs(UUID runId) {
        return readSse("/api/runs/" + runId + "/logs");
    }

    private List<SseEventReader.SseEvent> readSse(String path) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                .timeout(SSE_TIMEOUT)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        try {
            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) {
                throw new Agent4jHttpException(
                        response.statusCode(), String.join("\n", response.body().toList()));
            }
            return SseEventReader.read(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("Agent4J SSE 请求失败: " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent4J SSE 请求被中断: " + path, exception);
        }
    }

    private <T> T read(String method, String path, JsonNode body, Class<T> type) {
        String response = send(method, path, body, REQUEST_TIMEOUT);
        return parse(response, type);
    }

    private <T> List<T> readList(
            String method,
            String path,
            JsonNode body,
            Class<T> elementType) {
        String response = send(method, path, body, REQUEST_TIMEOUT);
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, elementType);
            return objectMapper.readValue(response, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent4J 响应 JSON 解析失败: " + path, exception);
        }
    }

    private <T> T parse(String response, Class<T> type) {
        try {
            return objectMapper.readValue(response, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent4J 响应 JSON 解析失败", exception);
        }
    }

    private String send(String method, String path, JsonNode body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(
                    writeJson(body), java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new Agent4jHttpException(response.statusCode(), response.body());
            }
            return response.body();
        } catch (IOException exception) {
            throw new IllegalStateException("Agent4J HTTP 请求失败: " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent4J HTTP 请求被中断: " + path, exception);
        }
    }

    private URI endpoint(String path) {
        String base = server.toString();
        String separator = base.endsWith("/") ? "" : "/";
        return URI.create(base + separator + (path.startsWith("/") ? path.substring(1) : path));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }

    private String writeJson(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent4J 请求 JSON 序列化失败", exception);
        }
    }
}
