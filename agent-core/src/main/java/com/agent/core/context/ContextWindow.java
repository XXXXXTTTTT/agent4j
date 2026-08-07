package com.agent.core.context;

import com.agent.core.llm.ChatMessage;

import java.util.List;
import java.util.Objects;

/** 已满足输入预算的模型消息与裁剪审计信息。 */
public record ContextWindow(
        List<ChatMessage> messages,
        int estimatedTokens,
        int droppedMessages,
        boolean summarized) {

    /** 冻结消息并校验审计计数。 */
    public ContextWindow {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens 不能为负数");
        }
        if (droppedMessages < 0) {
            throw new IllegalArgumentException("droppedMessages 不能为负数");
        }
    }
}
