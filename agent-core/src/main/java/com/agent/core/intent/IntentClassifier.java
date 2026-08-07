package com.agent.core.intent;

import com.agent.core.llm.ChatMessage;

import java.util.List;

/** 将当前任务和短期历史转换为强类型决策。 */
@FunctionalInterface
public interface IntentClassifier {

    /** 返回可供状态图消费的精确任务决策。 */
    TaskDecision classify(List<ChatMessage> history, String task);
}
