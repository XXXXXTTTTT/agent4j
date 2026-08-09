package com.agent.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmClientTest {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesStrictFunctionDefinitionWhenExplicitlyRequested() {
        JsonNode parameters = objectMapper.createObjectNode().put("type", "object");

        JsonNode tool = objectMapper.valueToTree(
                LlmClient.Tool.function("browser_action", "Browser action", parameters, true));

        assertThat(tool.at("/function/strict").booleanValue()).isTrue();
        assertThat(tool.at("/function/name").textValue()).isEqualTo("browser_action");
    }

    @Test
    void completesFunctionCallingRequestOnVirtualThread() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gateway.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicBoolean requestOnVirtualThread = new AtomicBoolean();
        AtomicBoolean modelMdcVisible = new AtomicBoolean();
        JsonNode parameters = objectMapper.createObjectNode()
                .put("type", "object")
                .set("properties", objectMapper.createObjectNode()
                        .set("id", objectMapper.createObjectNode().put("type", "integer")));
        LlmClient.Tool tool = LlmClient.Tool.function("lookup", "Lookup by id", parameters);
        LlmClient.ChatCompletionRequest request = new LlmClient.ChatCompletionRequest(
                "gpt-test",
                List.of(ChatMessage.user("Find item 42")),
                List.of(tool),
                objectMapper.valueToTree("auto"),
                0.2,
                true);

        server.expect(once(), requestTo("https://gateway.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(httpRequest -> requestOnVirtualThread.set(Thread.currentThread().isVirtual()))
                .andExpect(httpRequest -> modelMdcVisible.set(
                        "gpt-test".equals(MDC.get("modelName"))))
                .andExpect(content().json("""
                        {
                          "model": "gpt-test",
                          "messages": [{"role": "user", "content": "Find item 42"}],
                          "tools": [{
                            "type": "function",
                            "function": {
                              "name": "lookup",
                              "description": "Lookup by id",
                              "parameters": {
                                "type": "object",
                                "properties": {"id": {"type": "integer"}}
                              }
                            }
                          }],
                          "tool_choice": "auto",
                          "temperature": 0.2,
                          "stream": false
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "id": "chatcmpl-1",
                          "object": "chat.completion",
                          "created": 1720000000,
                          "model": "gpt-test",
                          "choices": [{
                            "index": 0,
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [{
                                "id": "call-1",
                                "type": "function",
                                "function": {"name": "lookup", "arguments": "{\\\"id\\\":42}"}
                              }]
                            },
                            "finish_reason": "tool_calls"
                          }],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                        }
                        """, MediaType.APPLICATION_JSON));

        try (LlmClient client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH)) {
            LlmClient.ChatCompletionResponse response = client.complete(request);

            assertThat(requestOnVirtualThread).isTrue();
            assertThat(response.id()).isEqualTo("chatcmpl-1");
            assertThat(response.choices().getFirst().finishReason()).isEqualTo("tool_calls");
            ChatMessage.ToolCall toolCall = response.choices().getFirst().message().toolCalls().getFirst();
            assertThat(toolCall.function().name()).isEqualTo("lookup");
            assertThat(toolCall.function().arguments()).isEqualTo("{\"id\":42}");
            assertThat(response.usage().totalTokens()).isEqualTo(15);
            assertThat(modelMdcVisible).isTrue();
            assertThat(MDC.get("modelName")).isNull();
        }
        server.verify();
    }

    @Test
    void streamsSseChunksAndStopsAtDoneOnVirtualThread() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gateway.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient.ChatCompletionRequest request = new LlmClient.ChatCompletionRequest(
                "gpt-test",
                List.of(ChatMessage.user("Hello")),
                List.of(),
                null,
                null,
                false);
        String sse = """
                data: {"id":"chunk-1","object":"chat.completion.chunk","created":1720000000,"model":"gpt-test","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}

                data: {"id":"chunk-1","object":"chat.completion.chunk","created":1720000000,"model":"gpt-test","choices":[{"index":0,"delta":{"content":"lo","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;

        server.expect(once(), requestTo("https://gateway.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "model": "gpt-test",
                          "messages": [{"role": "user", "content": "Hello"}],
                          "tools": [],
                          "stream": true
                        }
                        """, true))
                .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

        List<LlmClient.ChatCompletionChunk> chunks = new ArrayList<>();
        List<Boolean> callbackThreadTypes = new ArrayList<>();
        try (LlmClient client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH)) {
            client.stream(request, chunk -> {
                chunks.add(chunk);
                callbackThreadTypes.add(Thread.currentThread().isVirtual());
            });
        }

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().choices().getFirst().delta().content()).isEqualTo("Hel");
        LlmClient.ToolCallDelta toolCall = chunks.get(1)
                .choices().getFirst().delta().toolCalls().getFirst();
        assertThat(toolCall.index()).isZero();
        assertThat(toolCall.function().name()).isEqualTo("lookup");
        assertThat(callbackThreadTypes).containsExactly(true, true);
        server.verify();
    }

    @Test
    void wrapsHttpErrorsAndPreservesCause() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gateway.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient.ChatCompletionRequest request = new LlmClient.ChatCompletionRequest(
                "gpt-test",
                List.of(ChatMessage.user("Hello")),
                List.of(),
                null,
                null,
                false);

        server.expect(once(), requestTo("https://gateway.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"upstream unavailable\"}}"));

        try (LlmClient client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH)) {
            assertThatThrownBy(() -> client.complete(request))
                    .isInstanceOf(LlmClientException.class)
                    .hasCauseInstanceOf(RestClientResponseException.class);
        }
        server.verify();
    }

    @Test
    void preservesServiceUnavailableStatusForFallbackAndDiagnostics() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gateway.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://gateway.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"busy\"}}"));

        try (LlmClient client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH)) {
            assertThatThrownBy(() -> client.complete(streamingRequest()))
                    .isInstanceOf(LlmClientException.class)
                    .hasCauseInstanceOf(RestClientResponseException.class)
                    .satisfies(exception -> assertThat(
                            ((RestClientResponseException) exception.getCause()).getStatusCode())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }
        server.verify();
    }

    @Test
    void wrapsSseHttpErrorsAndPreservesCause() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gateway.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient.ChatCompletionRequest request = new LlmClient.ChatCompletionRequest(
                "gpt-test",
                List.of(ChatMessage.user("Hello")),
                List.of(),
                null,
                null,
                true);

        server.expect(once(), requestTo("https://gateway.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"upstream unavailable\"}}"));

        try (LlmClient client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH)) {
            assertThatThrownBy(() -> client.stream(request, chunk -> {
            }))
                    .isInstanceOf(LlmClientException.class)
                    .hasCauseInstanceOf(RestClientResponseException.class)
                    .satisfies(exception -> {
                        RestClientResponseException cause =
                                (RestClientResponseException) exception.getCause();
                        assertThat(cause.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(cause.getResponseBodyAsString()).contains("upstream unavailable");
                    });
        }
        server.verify();
    }

    @Test
    void joinsMultipleDataLinesWithinOneSseEvent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gateway.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmClient.ChatCompletionRequest request = streamingRequest();
        String sse = """
                data: {"id":"chunk-multi",
                data: "object":"chat.completion.chunk","created":1720000000,"model":"gpt-test","choices":[{"index":0,"delta":{"content":"joined"},"finish_reason":null}]}

                data: [DONE]

                """;

        server.expect(once(), requestTo("https://gateway.test/v1/chat/completions"))
                .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

        List<LlmClient.ChatCompletionChunk> chunks = new ArrayList<>();
        try (LlmClient client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH)) {
            client.stream(request, chunks::add);
        }

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().id()).isEqualTo("chunk-multi");
        assertThat(chunks.getFirst().choices().getFirst().delta().content()).isEqualTo("joined");
        server.verify();
    }

    @Test
    void preservesMalformedSseJsonCause() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gateway.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(once(), requestTo("https://gateway.test/v1/chat/completions"))
                .andRespond(withSuccess("data: not-json\n\n", MediaType.TEXT_EVENT_STREAM));

        try (LlmClient client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH)) {
            assertThatThrownBy(() -> client.stream(streamingRequest(), chunk -> {
            }))
                    .isInstanceOf(LlmClientException.class)
                    .hasRootCauseInstanceOf(JsonProcessingException.class);
        }
        server.verify();
    }

    @Test
    void preservesSseConsumerFailureCause() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gateway.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IllegalStateException failure = new IllegalStateException("consumer failed");
        String sse = """
                data: {"id":"chunk-1","object":"chat.completion.chunk","created":1720000000,"model":"gpt-test","choices":[]}

                data: [DONE]

                """;

        server.expect(once(), requestTo("https://gateway.test/v1/chat/completions"))
                .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

        try (LlmClient client = new LlmClient(builder.build(), objectMapper, CHAT_COMPLETIONS_PATH)) {
            assertThatThrownBy(() -> client.stream(streamingRequest(), chunk -> {
                throw failure;
            }))
                    .isInstanceOf(LlmClientException.class)
                    .hasCause(failure);
        }
        server.verify();
    }

    private LlmClient.ChatCompletionRequest streamingRequest() {
        return new LlmClient.ChatCompletionRequest(
                "gpt-test",
                List.of(ChatMessage.user("Hello")),
                List.of(),
                null,
                null,
                true);
    }
}
