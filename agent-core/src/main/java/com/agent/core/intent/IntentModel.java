package com.agent.core.intent;

import com.agent.core.llm.ChatMessage;

import java.util.List;

/** 接收完整路由消息并返回原始分类文本的窄端口。 */
@FunctionalInterface
public interface IntentModel {

    /** 调用语义模型并返回其原始文本。 */
    String classify(List<ChatMessage> messages);
}
