package com.agent.core.context;

import com.agent.core.llm.ChatMessage;

import java.util.List;
import java.util.Objects;

/** 一次模型调用的上下文窗口输入。 */
public record ContextWindowRequest(
        ChatMessage systemMessage,
        List<ChatMessage> history,
        ChatMessage currentUserMessage,
        ChatMessage latestToolError,
        int maxInputTokens,
        int summaryMaxTokens) {

    /** 校验消息角色、冻结历史并校验预算。 */
    public ContextWindowRequest {
        Objects.requireNonNull(systemMessage, "systemMessage 不能为空");
        Objects.requireNonNull(currentUserMessage, "currentUserMessage 不能为空");
        if (systemMessage.role() != ChatMessage.Role.SYSTEM) {
            throw new IllegalArgumentException("systemMessage role 必须是 SYSTEM");
        }
        if (currentUserMessage.role() != ChatMessage.Role.USER) {
            throw new IllegalArgumentException("currentUserMessage role 必须是 USER");
        }
        if (latestToolError != null && latestToolError.role() != ChatMessage.Role.TOOL) {
            throw new IllegalArgumentException("latestToolError role 必须是 TOOL");
        }
        history = List.copyOf(Objects.requireNonNull(history, "history 不能为空"));
        if (history.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("history 不能包含 null");
        }
        if (maxInputTokens < 1) {
            throw new IllegalArgumentException("maxInputTokens 必须大于 0");
        }
        if (summaryMaxTokens < 0 || summaryMaxTokens > maxInputTokens) {
            throw new IllegalArgumentException(
                    "summaryMaxTokens 必须在 0 到 maxInputTokens 之间");
        }
    }
}
