package com.agent.core.context;

import com.agent.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextWindowManagerTest {

    private final TokenEstimator estimator = message -> switch (message.role()) {
        case SYSTEM -> 2;
        case USER, ASSISTANT -> 3;
        case TOOL -> 4;
    };

    @Test
    void preservesProtectedMessagesAndSummarizesOldHistory() {
        ContextWindowManager manager = new ContextWindowManager(
                estimator, (messages, limit) -> "旧对话摘要");

        ContextWindow result = manager.fit(new ContextWindowRequest(
                ChatMessage.system("系统"),
                List.of(
                        ChatMessage.user("旧问题"),
                        ChatMessage.assistant("旧回答"),
                        ChatMessage.user("新问题"),
                        ChatMessage.assistant("新回答")),
                ChatMessage.user("当前问题"),
                ChatMessage.tool("tool-1", "最近工具错误"),
                15,
                3));

        assertThat(result.messages()).contains(ChatMessage.system("系统"));
        assertThat(result.messages()).contains(ChatMessage.user("当前问题"));
        assertThat(result.messages()).contains(
                ChatMessage.tool("tool-1", "最近工具错误"));
        assertThat(result.messages()).anyMatch(message ->
                message.role() == ChatMessage.Role.SYSTEM
                        && ((ChatMessage.TextContent) message.content())
                        .text().contains("旧对话摘要"));
        assertThat(result.droppedMessages()).isEqualTo(3);
        assertThat(result.summarized()).isTrue();
        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(15);
    }

    @Test
    void rejectsBudgetSmallerThanProtectedMessages() {
        ContextWindowManager manager = new ContextWindowManager(
                estimator, (messages, limit) -> "");

        assertThatThrownBy(() -> manager.fit(new ContextWindowRequest(
                ChatMessage.system("系统"),
                List.of(),
                ChatMessage.user("当前"),
                ChatMessage.tool("tool-1", "错误"),
                8,
                0)))
                .isInstanceOf(ContextBudgetExceededException.class)
                .hasMessageContaining("受保护消息");
    }

    @Test
    void keepsAllHistoryWithoutCallingSummaryWhenItFits() {
        ContextWindowManager manager = new ContextWindowManager(estimator, (messages, limit) -> {
            throw new AssertionError("完整历史可放入预算时不应调用摘要");
        });
        List<ChatMessage> history = List.of(
                ChatMessage.user("旧问题"), ChatMessage.assistant("旧回答"));

        ContextWindow result = manager.fit(new ContextWindowRequest(
                ChatMessage.system("系统"),
                history,
                ChatMessage.user("当前"),
                null,
                20,
                3));

        assertThat(result.messages()).containsSubsequence(history);
        assertThat(result.droppedMessages()).isZero();
        assertThat(result.summarized()).isFalse();
    }
}
