package com.agent.core.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 按精确 OpenAI 结构反序列化聊天消息内容。 */
final class ChatMessageContentDeserializer extends StdDeserializer<ChatMessage.Content> {

    private static final Set<String> TEXT_FIELDS = Set.of("type", "text");
    private static final Set<String> IMAGE_PART_FIELDS = Set.of("type", "image_url");
    private static final Set<String> IMAGE_URL_FIELDS = Set.of("url", "detail");

    ChatMessageContentDeserializer() {
        super(ChatMessage.Content.class);
    }

    /** 解析纯文本、内容块数组或 null。 */
    @Override
    public ChatMessage.Content deserialize(
            JsonParser parser,
            DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return new ChatMessage.TextContent(node.textValue());
        }
        if (!node.isArray()) {
            throw mappingException(parser, "content 必须是字符串、数组或 null");
        }
        if (node.isEmpty()) {
            throw mappingException(parser, "content 数组不能为空");
        }

        List<ChatMessage.ContentPart> parts = new ArrayList<>();
        for (JsonNode partNode : node) {
            parts.add(parsePart(partNode, parser));
        }
        return new ChatMessage.MultimodalContent(parts);
    }

    /** 返回 JSON null 对应的消息内容。 */
    @Override
    public ChatMessage.Content getNullValue(DeserializationContext context) {
        return null;
    }

    private ChatMessage.ContentPart parsePart(
            JsonNode node,
            JsonParser parser) throws JsonMappingException {
        if (!node.isObject()) {
            throw mappingException(parser, "content 数组元素必须是对象");
        }
        JsonNode typeNode = node.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            throw mappingException(parser, "content 数组元素缺少字符串 type");
        }
        return switch (typeNode.textValue()) {
            case "text" -> parseTextPart(node, parser);
            case "image_url" -> parseImagePart(node, parser);
            default -> throw mappingException(
                    parser, "未知 content part type: " + typeNode.textValue());
        };
    }

    private ChatMessage.TextPart parseTextPart(
            JsonNode node,
            JsonParser parser) throws JsonMappingException {
        requireExactFields(node, TEXT_FIELDS, parser);
        JsonNode textNode = node.get("text");
        if (textNode == null || !textNode.isTextual()) {
            throw mappingException(parser, "text part 缺少字符串 text");
        }
        return new ChatMessage.TextPart(textNode.textValue());
    }

    private ChatMessage.ImageUrlPart parseImagePart(
            JsonNode node,
            JsonParser parser) throws JsonMappingException {
        requireExactFields(node, IMAGE_PART_FIELDS, parser);
        JsonNode imageNode = node.get("image_url");
        if (imageNode == null || !imageNode.isObject()) {
            throw mappingException(parser, "image_url part 缺少对象 image_url");
        }
        requireExactFields(imageNode, IMAGE_URL_FIELDS, parser);

        JsonNode urlNode = imageNode.get("url");
        JsonNode detailNode = imageNode.get("detail");
        if (urlNode == null || !urlNode.isTextual() || urlNode.textValue().isBlank()) {
            throw mappingException(parser, "image_url.url 必须是非空字符串");
        }
        if (detailNode == null || !detailNode.isTextual()) {
            throw mappingException(parser, "image_url.detail 必须是字符串");
        }

        ChatMessage.ImageDetail detail;
        try {
            detail = ChatMessage.ImageDetail.fromJson(detailNode.textValue());
        } catch (IllegalArgumentException exception) {
            throw mappingException(parser, exception.getMessage());
        }
        return new ChatMessage.ImageUrlPart(
                new ChatMessage.ImageUrl(urlNode.textValue(), detail));
    }

    private void requireExactFields(
            JsonNode node,
            Set<String> expected,
            JsonParser parser) throws JsonMappingException {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw mappingException(
                    parser,
                    "字段必须精确为 " + expected + "，实际为 " + actual);
        }
    }

    private JsonMappingException mappingException(JsonParser parser, String message) {
        return JsonMappingException.from(parser, message);
    }
}
