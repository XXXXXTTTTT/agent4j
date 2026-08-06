package com.agent.core.conversation;

import com.agent.core.llm.ChatMessage;

import java.util.List;
import java.util.Objects;

/** 供一次 Agent Run 使用的有界短期会话上下文。 */
public record ConversationContext(
        List<ChatMessage> messages,
        int turnCount,
        boolean truncated) {

    /** 校验并冻结上下文消息。 */
    public ConversationContext {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        if (turnCount < 0) {
            throw new IllegalArgumentException("turnCount 不能为负数");
        }
    }
}
