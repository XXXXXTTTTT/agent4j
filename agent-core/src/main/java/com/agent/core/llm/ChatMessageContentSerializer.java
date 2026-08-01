package com.agent.core.llm;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/** 按 OpenAI 协议序列化聊天消息内容。 */
final class ChatMessageContentSerializer extends JsonSerializer<ChatMessage.Content> {

    /** 序列化纯文本或多模态内容。 */
    @Override
    public void serialize(
            ChatMessage.Content value,
            JsonGenerator generator,
            SerializerProvider serializers) throws IOException {
        switch (value) {
            case ChatMessage.TextContent textContent ->
                    generator.writeString(textContent.text());
            case ChatMessage.MultimodalContent multimodalContent -> {
                generator.writeStartArray();
                for (ChatMessage.ContentPart part : multimodalContent.parts()) {
                    writePart(part, generator);
                }
                generator.writeEndArray();
            }
        }
    }

    private void writePart(
            ChatMessage.ContentPart part,
            JsonGenerator generator) throws IOException {
        generator.writeStartObject();
        switch (part) {
            case ChatMessage.TextPart textPart -> {
                generator.writeStringField("type", "text");
                generator.writeStringField("text", textPart.text());
            }
            case ChatMessage.ImageUrlPart imageUrlPart -> {
                generator.writeStringField("type", "image_url");
                generator.writeObjectFieldStart("image_url");
                generator.writeStringField("url", imageUrlPart.imageUrl().url());
                generator.writeStringField(
                        "detail", imageUrlPart.imageUrl().detail().jsonValue());
                generator.writeEndObject();
            }
        }
        generator.writeEndObject();
    }
}
