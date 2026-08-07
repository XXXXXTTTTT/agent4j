package com.agent.core.context;

import com.agent.core.llm.ChatMessage;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 使用 UTF-8 字节数提供确定性、与语言无关的 token 近似值。 */
public final class Utf8TokenEstimator implements TokenEstimator {

    private static final int MESSAGE_OVERHEAD = 4;

    /** 按 UTF-8 字节数除以四向上取整，并加入消息协议开销。 */
    @Override
    public int estimate(ChatMessage message) {
        Objects.requireNonNull(message, "message 不能为空");
        StringBuilder protocolText = new StringBuilder();
        appendContent(protocolText, message.content());
        append(protocolText, message.name());
        append(protocolText, message.toolCallId());
        for (ChatMessage.ToolCall toolCall : message.toolCalls()) {
            append(protocolText, toolCall.id());
            append(protocolText, toolCall.type());
            append(protocolText, toolCall.function().name());
            append(protocolText, toolCall.function().arguments());
        }
        int bytes = protocolText.toString().getBytes(StandardCharsets.UTF_8).length;
        return MESSAGE_OVERHEAD + Math.max(1, (bytes + 3) / 4);
    }

    private void appendContent(StringBuilder target, ChatMessage.Content content) {
        if (content instanceof ChatMessage.TextContent textContent) {
            append(target, textContent.text());
        } else if (content instanceof ChatMessage.MultimodalContent multimodalContent) {
            for (ChatMessage.ContentPart part : multimodalContent.parts()) {
                if (part instanceof ChatMessage.TextPart textPart) {
                    append(target, textPart.text());
                } else if (part instanceof ChatMessage.ImageUrlPart imagePart) {
                    append(target, imagePart.imageUrl().url());
                    append(target, imagePart.imageUrl().detail().jsonValue());
                }
            }
        }
    }

    private void append(StringBuilder target, String value) {
        if (value != null) {
            target.append(value);
        }
    }
}
