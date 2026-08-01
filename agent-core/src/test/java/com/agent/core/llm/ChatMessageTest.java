package com.agent.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesRolesUsingOpenAiValues() throws Exception {
        assertThat(roleValue(ChatMessage.system("rules"))).isEqualTo("system");
        assertThat(roleValue(ChatMessage.user("question"))).isEqualTo("user");
        assertThat(roleValue(ChatMessage.assistant("answer"))).isEqualTo("assistant");
        assertThat(roleValue(ChatMessage.tool("call-1", "result"))).isEqualTo("tool");
    }

    @Test
    void createsFunctionCallingAssistantMessage() throws Exception {
        ChatMessage.ToolCall toolCall = new ChatMessage.ToolCall(
                "call-1",
                "function",
                new ChatMessage.FunctionCall("lookup", "{\"id\":42}"));

        ChatMessage message = ChatMessage.assistantToolCalls(List.of(toolCall));
        JsonNode json = objectMapper.valueToTree(message);

        assertThat(json.get("role").textValue()).isEqualTo("assistant");
        assertThat(json.has("content")).isFalse();
        assertThat(json.get("tool_calls").get(0).get("id").textValue()).isEqualTo("call-1");
        assertThat(json.get("tool_calls").get(0).get("function").get("name").textValue())
                .isEqualTo("lookup");
        assertThat(json.get("tool_calls").get(0).get("function").get("arguments").textValue())
                .isEqualTo("{\"id\":42}");
    }

    @Test
    void createsToolResultMessageWithExactToolCallIdKey() {
        JsonNode json = objectMapper.valueToTree(ChatMessage.tool("call-1", "result"));

        assertThat(json.get("role").textValue()).isEqualTo("tool");
        assertThat(json.get("tool_call_id").textValue()).isEqualTo("call-1");
        assertThat(json.get("content").textValue()).isEqualTo("result");
        assertThat(json.has("tool_calls")).isFalse();
    }

    @Test
    void copiesAndFreezesToolCalls() {
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        toolCalls.add(new ChatMessage.ToolCall(
                "call-1",
                "function",
                new ChatMessage.FunctionCall("lookup", "{}")));

        ChatMessage message = ChatMessage.assistantToolCalls(toolCalls);
        toolCalls.clear();

        assertThat(message.toolCalls()).hasSize(1);
        assertThatThrownBy(() -> message.toolCalls().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void serializesAndDeserializesMultimodalUserMessage() throws Exception {
        ChatMessage message = ChatMessage.userMultimodal(List.of(
                new ChatMessage.TextPart("检查页面"),
                new ChatMessage.ImageUrlPart(new ChatMessage.ImageUrl(
                        "data:image/png;base64,AQID",
                        ChatMessage.ImageDetail.HIGH))));

        JsonNode json = objectMapper.valueToTree(message);

        assertThat(json.get("role").textValue()).isEqualTo("user");
        assertThat(json.at("/content/0/type").textValue()).isEqualTo("text");
        assertThat(json.at("/content/0/text").textValue()).isEqualTo("检查页面");
        assertThat(json.at("/content/1/type").textValue()).isEqualTo("image_url");
        assertThat(json.at("/content/1/image_url/url").textValue())
                .isEqualTo("data:image/png;base64,AQID");
        assertThat(json.at("/content/1/image_url/detail").textValue()).isEqualTo("high");

        ChatMessage decoded = objectMapper.treeToValue(json, ChatMessage.class);
        assertThat(decoded).isEqualTo(message);
    }

    @Test
    void copiesAndFreezesMultimodalParts() {
        List<ChatMessage.ContentPart> parts = new ArrayList<>();
        parts.add(new ChatMessage.TextPart("检查页面"));

        ChatMessage message = ChatMessage.userMultimodal(parts);
        parts.clear();

        ChatMessage.MultimodalContent content =
                (ChatMessage.MultimodalContent) message.content();
        assertThat(content.parts()).hasSize(1);
        assertThatThrownBy(() -> content.parts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEmptyMultimodalPartsAndBlankImageUrl() {
        assertThatThrownBy(() -> ChatMessage.userMultimodal(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parts");
        assertThatThrownBy(() -> new ChatMessage.ImageUrl(
                " ", ChatMessage.ImageDetail.AUTO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void rejectsUnknownOrIncompleteMultimodalJsonWithoutGuessing() {
        assertMalformedMultimodalJson("""
                {"role":"user","content":[{"type":"Text","text":"检查页面"}]}
                """);
        assertMalformedMultimodalJson("""
                {"role":"user","content":[{"type":"image_url"}]}
                """);
        assertMalformedMultimodalJson("""
                {"role":"user","content":[{
                  "type":"image_url",
                  "image_url":{"url":"data:image/png;base64,AQID","detail":"HIGH"}
                }]}
                """);
    }

    private void assertMalformedMultimodalJson(String json) {
        assertThatThrownBy(() -> objectMapper.readValue(json, ChatMessage.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    private String roleValue(ChatMessage message) {
        return objectMapper.valueToTree(message).get("role").textValue();
    }
}
