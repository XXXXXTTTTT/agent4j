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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.function.Consumer;

/** 使用 Java 21 HttpClient 调用 Agent4J REST 与 SSE API。 */
public final class Agent4jHttpClient implements Agent4jClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SSE_TIMEOUT = Duration.ofHours(12);

    private final HttpClient httpClient;
    private final List<URI> servers;
    private final ObjectMapper objectMapper;
    private volatile URI activeServer;

    public Agent4jHttpClient(HttpClient httpClient, URI server, ObjectMapper objectMapper) {
        this(httpClient, LoopbackServerEndpoints.forServer(server), objectMapper);
    }

    Agent4jHttpClient(
            HttpClient httpClient,
            Collection<URI> servers,
            ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient 不能为空");
        this.servers = List.copyOf(Objects.requireNonNull(servers, "servers 不能为空"));
        if (this.servers.isEmpty()) {
            throw new IllegalArgumentException("servers 不能为空");
        }
        this.servers.forEach(server -> Objects.requireNonNull(server, "server 不能为空"));
        this.activeServer = this.servers.getFirst();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public Actor identity() {
        RuntimeException firstFailure = null;
        Agent4jHttpException firstHttpFailure = null;
        for (URI endpoint : servers) {
            try {
                Actor actor = requireIdentity(parse(
                        send(endpoint, "GET", "/api/identity", null, REQUEST_TIMEOUT),
                        Agent4jClient.Actor.class));
                activeServer = endpoint;
                return actor;
            } catch (RuntimeException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                }
                if (firstHttpFailure == null
                        && exception instanceof Agent4jHttpException httpFailure) {
                    firstHttpFailure = httpFailure;
                }
            }
        }
        if (firstHttpFailure != null) {
            throw firstHttpFailure;
        }
        if (servers.size() == 1 && firstFailure != null) {
            throw firstFailure;
        }
        throw new IllegalStateException(
                "Agent4J 身份请求失败，已尝试本机回环服务端，请检查 8080 端口占用情况",
                firstFailure);
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
        List<SseEventReader.SseEvent> events = new ArrayList<>();
        followTrace(runId, events::add);
        return List.copyOf(events);
    }

    @Override
    public List<SseEventReader.SseEvent> readLogs(UUID runId) {
        List<SseEventReader.SseEvent> events = new ArrayList<>();
        followLogs(runId, events::add);
        return List.copyOf(events);
    }

    @Override
    public void followTrace(
            UUID runId,
            Consumer<SseEventReader.SseEvent> eventConsumer) {
        followSse("/api/runs/" + runId + "/events", eventConsumer);
    }

    @Override
    public void followLogs(
            UUID runId,
            Consumer<SseEventReader.SseEvent> eventConsumer) {
        followSse("/api/runs/" + runId + "/logs", eventConsumer);
    }

    private void followSse(
            String path,
            Consumer<SseEventReader.SseEvent> eventConsumer) {
        Objects.requireNonNull(eventConsumer, "eventConsumer 不能为空");
        HttpRequest request = HttpRequest.newBuilder(endpoint(activeServer, path))
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
            SseEventReader.follow(response.body(), eventConsumer);
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

    private Actor requireIdentity(Actor actor) {
        if (actor.userId() == null || actor.userId().isBlank()
                || actor.displayName() == null || actor.displayName().isBlank()) {
            throw new IllegalStateException(
                    "Agent4J 身份响应必须包含非空 userId 和 displayName");
        }
        return actor;
    }

    private String send(String method, String path, JsonNode body, Duration timeout) {
        return send(activeServer, method, path, body, timeout);
    }

    private String send(URI base, String method, String path, JsonNode body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(base, path))
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

    private URI endpoint(URI baseUri, String path) {
        String base = baseUri.toString();
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
