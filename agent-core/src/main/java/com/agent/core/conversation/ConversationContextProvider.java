package com.agent.core.conversation;

import java.util.UUID;

/** 从权威会话存储读取短期上下文的核心端口。 */
@FunctionalInterface
public interface ConversationContextProvider {

    /** 读取指定会话的有界历史。 */
    ConversationContext load(
            UUID conversationId,
            String userId,
            int maxTurns,
            int maxCharacters);
}
