package com.agent.core.engine;

import com.agent.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentStateTest {

    @Test
    void copiesConstructorCollections() {
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("question")));
        Map<String, String> variables = new LinkedHashMap<>(Map.of("status", "new"));
        List<String> trace = new ArrayList<>(List.of("start"));

        AgentState state = new AgentState(messages, variables, trace);
        messages.add(ChatMessage.assistant("answer"));
        variables.put("status", "changed");
        trace.add("changed");

        assertThat(state.messages()).containsExactly(ChatMessage.user("question"));
        assertThat(state.variables()).containsEntry("status", "new");
        assertThat(state.trace()).containsExactly("start");
    }

    @Test
    void exposesUnmodifiableCollections() {
        AgentState state = new AgentState(
                List.of(ChatMessage.user("question")),
                Map.of("status", "new"),
                List.of("start"));

        assertThatThrownBy(() -> state.messages().add(ChatMessage.assistant("answer")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> state.variables().put("status", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> state.trace().add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void updateMethodsReturnNewStates() {
        AgentState original = AgentState.empty();

        AgentState updated = original
                .withMessage(ChatMessage.user("question"))
                .withVariable("action", "tool")
                .withTraceEntry("planner");

        assertThat(original.messages()).isEmpty();
        assertThat(original.variables()).isEmpty();
        assertThat(original.trace()).isEmpty();
        assertThat(updated.messages()).containsExactly(ChatMessage.user("question"));
        assertThat(updated.variables()).containsEntry("action", "tool");
        assertThat(updated.trace()).containsExactly("planner");
    }
}
