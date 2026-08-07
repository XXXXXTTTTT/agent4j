package com.agent.core.context;

import com.agent.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8TokenEstimatorTest {

    @Test
    void estimatesUtf8TextWithPerMessageOverhead() {
        Utf8TokenEstimator estimator = new Utf8TokenEstimator();

        assertThat(estimator.estimate(ChatMessage.user("abcd"))).isEqualTo(5);
        assertThat(estimator.estimate(ChatMessage.user("你好"))).isEqualTo(6);
    }

    @Test
    void includesToolCallProtocolText() {
        Utf8TokenEstimator estimator = new Utf8TokenEstimator();
        ChatMessage message = ChatMessage.assistantToolCalls(List.of(new ChatMessage.ToolCall(
                "call-1", "function", new ChatMessage.FunctionCall("search", "{\"q\":\"x\"}"))));

        assertThat(estimator.estimate(message)).isGreaterThan(4);
    }
}
