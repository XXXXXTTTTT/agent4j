package com.agent.web.conversation;

import com.agent.core.conversation.ConversationContext;
import com.agent.core.conversation.ConversationContextProvider;
import com.agent.core.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 将 PostgreSQL 完成轮次组装为有界的核心会话上下文。 */
public final class JdbcConversationContextProvider implements ConversationContextProvider {

    private final ConversationRepository repository;

    /** 创建上下文读取器。 */
    public JdbcConversationContextProvider(ConversationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    /** 只返回完成轮次，并按完整用户/助手对从旧到新截断。 */
    @Override
    public ConversationContext load(
            UUID conversationId,
            String userId,
            int maxTurns,
            int maxCharacters) {
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空白");
        }
        if (maxTurns < 0 || maxCharacters < 0) {
            throw new IllegalArgumentException("上下文预算不能为负数");
        }
        List<ConversationTurnRecord> completed = repository.findTurns(conversationId, userId).stream()
                .filter(turn -> turn.status() == ConversationTurnStatus.COMPLETED)
                .toList();
        int total = completed.size();
        List<ConversationTurnRecord> retained = new ArrayList<>();
        int characters = 0;
        for (int index = completed.size() - 1; index >= 0 && retained.size() < maxTurns; index--) {
            ConversationTurnRecord turn = completed.get(index);
            int pairCharacters = turn.userContent().length() + turn.assistantContent().length();
            if (characters + pairCharacters > maxCharacters) {
                break;
            }
            retained.add(turn);
            characters += pairCharacters;
        }
        retained = retained.reversed();
        List<ChatMessage> messages = new ArrayList<>(retained.size() * 2);
        for (ConversationTurnRecord turn : retained) {
            messages.add(ChatMessage.user(turn.userContent()));
            messages.add(ChatMessage.assistant(turn.assistantContent()));
        }
        return new ConversationContext(
                messages,
                total,
                retained.size() < total);
    }
}
