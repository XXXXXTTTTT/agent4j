package com.agent.core.context;

import com.agent.core.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 按受保护消息、最近历史和摘要优先级组装模型上下文。 */
public final class ContextWindowManager {

    private static final String SUMMARY_PREFIX = "历史对话摘要：\n";

    private final TokenEstimator estimator;
    private final ContextSummaryProvider summaryProvider;

    /** 创建无共享可变状态的上下文窗口管理器。 */
    public ContextWindowManager(
            TokenEstimator estimator,
            ContextSummaryProvider summaryProvider) {
        this.estimator = Objects.requireNonNull(estimator, "estimator 不能为空");
        this.summaryProvider = Objects.requireNonNull(
                summaryProvider, "summaryProvider 不能为空");
    }

    /** 保留受保护消息，并在输入预算内选择最近历史和可用摘要。 */
    public ContextWindow fit(ContextWindowRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        int protectedTokens = estimate(request.systemMessage())
                + estimate(request.currentUserMessage())
                + estimateNullable(request.latestToolError());
        if (protectedTokens > request.maxInputTokens()) {
            throw new ContextBudgetExceededException(
                    protectedTokens, request.maxInputTokens());
        }

        int available = request.maxInputTokens() - protectedTokens;
        int historyTokens = estimateAll(request.history());
        if (historyTokens <= available) {
            return result(request, null, request.history(), 0);
        }

        int historyBudget = Math.max(0, available - request.summaryMaxTokens());
        List<ChatMessage> retainedReversed = new ArrayList<>();
        int retainedTokens = 0;
        for (int index = request.history().size() - 1; index >= 0; index--) {
            ChatMessage message = request.history().get(index);
            int messageTokens = estimate(message);
            if (retainedTokens + messageTokens > historyBudget) {
                break;
            }
            retainedReversed.add(message);
            retainedTokens += messageTokens;
        }
        List<ChatMessage> retained = retainedReversed.reversed();
        int droppedCount = request.history().size() - retained.size();
        List<ChatMessage> dropped = request.history().subList(0, droppedCount);
        ChatMessage summaryMessage = summaryMessage(
                dropped,
                request.summaryMaxTokens(),
                available - retainedTokens);
        return result(request, summaryMessage, retained, droppedCount);
    }

    private ChatMessage summaryMessage(
            List<ChatMessage> dropped,
            int summaryMaxTokens,
            int availableTokens) {
        if (dropped.isEmpty() || summaryMaxTokens == 0) {
            return null;
        }
        String summary = summaryProvider.summarize(List.copyOf(dropped), summaryMaxTokens);
        if (summary == null || summary.isBlank()) {
            return null;
        }
        ChatMessage message = ChatMessage.system(SUMMARY_PREFIX + summary);
        return estimate(message) <= availableTokens ? message : null;
    }

    private ContextWindow result(
            ContextWindowRequest request,
            ChatMessage summary,
            List<ChatMessage> retained,
            int droppedCount) {
        List<ChatMessage> messages = new ArrayList<>(retained.size() + 4);
        messages.add(request.systemMessage());
        if (summary != null) {
            messages.add(summary);
        }
        messages.addAll(retained);
        if (request.latestToolError() != null) {
            messages.add(request.latestToolError());
        }
        messages.add(request.currentUserMessage());
        int estimatedTokens = estimateAll(messages);
        if (estimatedTokens > request.maxInputTokens()) {
            throw new IllegalStateException("上下文窗口结果超过输入预算");
        }
        return new ContextWindow(
                messages, estimatedTokens, droppedCount, summary != null);
    }

    private int estimateNullable(ChatMessage message) {
        return message == null ? 0 : estimate(message);
    }

    private int estimateAll(List<ChatMessage> messages) {
        return messages.stream().mapToInt(this::estimate).sum();
    }

    private int estimate(ChatMessage message) {
        int tokens = estimator.estimate(message);
        if (tokens < 1) {
            throw new IllegalStateException("TokenEstimator 必须返回正整数");
        }
        return tokens;
    }
}
