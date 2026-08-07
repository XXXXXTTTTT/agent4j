package com.agent.core.context;

import com.agent.core.llm.ChatMessage;

/** 估算单条模型消息占用的 token 数。 */
@FunctionalInterface
public interface TokenEstimator {

    /** 返回包含消息协议开销的正整数估算值。 */
    int estimate(ChatMessage message);
}
