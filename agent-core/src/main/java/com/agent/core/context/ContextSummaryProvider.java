package com.agent.core.context;

import com.agent.core.llm.ChatMessage;

import java.util.List;

/** 为被上下文窗口裁剪的历史消息生成摘要。 */
@FunctionalInterface
public interface ContextSummaryProvider {

    /** 返回不超过调用方预算目标的摘要文本。 */
    String summarize(List<ChatMessage> messages, int maxTokens);
}
