package com.agent.web.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiEmbeddingModelTest {

    private HttpServer server;
    private AtomicReference<String> requestPath;
    private AtomicReference<String> authorization;
    private AtomicReference<String> requestBody;

    @BeforeEach
    void startServer() throws IOException {
        requestPath = new AtomicReference<>();
        authorization = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsExactEmbeddingRequestAndReadsOneEightDimensionalVector() throws Exception {
        register("""
                {"data":[{"index":0,"embedding":[0,1,2,3,4,5,6,7]}]}
                """);
        OpenAiEmbeddingModel model = model();

        assertThat(model.embed("source text"))
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(requestPath.get()).isEqualTo("/v1/embeddings");
        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
        assertThat(new ObjectMapper().readTree(requestBody.get()))
                .isEqualTo(new ObjectMapper().readTree(
                        """
                        {"model":"embed-test","input":["source text"],"dimensions":8}
                        """));
    }

    @Test
    void rejectsDuplicateOrNonFiniteEmbeddingData() {
        register("""
                {"data":[{"index":0,"embedding":[0,1,2,3,4,5,6,7]},
                {"index":0,"embedding":[0,1,2,3,4,5,6,7]}]}
                """);
        assertThatThrownBy(() -> model().embed("source"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("data");

        register("""
                {"data":[{"index":0,"embedding":[0,1,2,3,4,5,6,"NaN"]}]}
                """);
        assertThatThrownBy(() -> model().embed("source"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("有限");
    }

    @Test
    void rejectsTrailingJsonAndPreservesHttpFailureCause() {
        register("""
                {"data":[{"index":0,"embedding":[0,1,2,3,4,5,6,7]}]}{"extra":true}
                """);
        assertThatThrownBy(() -> model().embed("source"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("尾随");

        register(exchange -> {
            exchange.sendResponseHeaders(503, 0);
            exchange.getResponseBody().write("unavailable".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        assertThatThrownBy(() -> model().embed("source"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    private OpenAiEmbeddingModel model() {
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-secret")
                .build();
        return new OpenAiEmbeddingModel(
                client,
                new ObjectMapper(),
                "/v1/embeddings",
                "embed-test",
                baseUrl + "/v1/embeddings");
    }

    private void register(String body) {
        register(exchange -> {
            capture(exchange);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void register(com.sun.net.httpserver.HttpHandler handler) {
        try {
            server.removeContext("/");
        } catch (IllegalArgumentException ignored) {
            // 首次注册没有旧上下文。
        }
        server.createContext("/", handler);
    }

    private void capture(HttpExchange exchange) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

}
