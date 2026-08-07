package com.agent.core.engine;

import com.agent.core.harness.HarnessEvent;
import com.agent.core.harness.HarnessEventType;
import com.agent.core.harness.HarnessHookChain;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateGraphHarnessTest {

    private static final ExecutionBudget BUDGET = new ExecutionBudget(
            Duration.ofSeconds(2), Duration.ofSeconds(1), 100, 5, 3);

    @Test
    void publishesNodeAndToolEventsInExecutionOrder() {
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        HarnessHookChain hooks = new HarnessHookChain(List.of(events::add));

        try (StateGraph graph = new StateGraph(BUDGET, InterruptPolicy.never(), hooks)) {
            graph.addNode("ops", state -> {
                        String output = NodeExecutionContext.callTool(
                                "terminal",
                                Map.of("command", "echo ok"),
                                () -> "ok");
                        return state.withVariable("output", output);
                    })
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("ops");

            AgentState result = graph.execute(AgentState.empty());

            assertThat(result.variables()).containsEntry("output", "ok");
        }
        assertThat(events).extracting(HarnessEvent::eventType).containsExactly(
                HarnessEventType.BEFORE_NODE,
                HarnessEventType.BEFORE_TOOL,
                HarnessEventType.AFTER_TOOL,
                HarnessEventType.AFTER_NODE);
        assertThat(events.subList(1, 3)).allSatisfy(event ->
                assertThat(event.metadata())
                        .containsEntry("toolName", "terminal")
                        .containsEntry("command", "echo ok"));
    }

    @Test
    void publishesToolAndNodeFailuresButPreservesOriginalCause() {
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        IOException cause = new IOException("terminal unavailable");

        try (StateGraph graph = new StateGraph(
                BUDGET,
                InterruptPolicy.never(),
                new HarnessHookChain(List.of(events::add)))) {
            graph.addNode("ops", state -> NodeExecutionContext.callTool(
                            "terminal", Map.of(), () -> { throw cause; }))
                    .addEdge("ops", StateGraph.END)
                    .setEntryPoint("ops");

            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .isInstanceOf(GraphExecutionException.class)
                    .hasCause(cause);
        }
        assertThat(events).extracting(HarnessEvent::eventType).containsExactly(
                HarnessEventType.BEFORE_NODE,
                HarnessEventType.BEFORE_TOOL,
                HarnessEventType.FAILURE,
                HarnessEventType.FAILURE);
    }

    @Test
    void publishesBudgetEventAfterLastCompletedNode() {
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        ExecutionBudget noProgressBudget = new ExecutionBudget(
                Duration.ofSeconds(2), Duration.ofSeconds(1), 100, 5, 1);

        try (StateGraph graph = new StateGraph(
                noProgressBudget,
                InterruptPolicy.never(),
                new HarnessHookChain(List.of(events::add)))) {
            graph.addNode("loop", state -> state.withTraceEntry("loop"))
                    .addEdge("loop", "loop")
                    .setEntryPoint("loop");

            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .isInstanceOf(ExecutionBudgetExceededException.class);
        }
        assertThat(events).extracting(HarnessEvent::eventType).containsExactly(
                HarnessEventType.BEFORE_NODE,
                HarnessEventType.AFTER_NODE,
                HarnessEventType.BUDGET_EXHAUSTED);
        assertThat(events.getLast().metadata())
                .containsEntry("reason", "NO_PROGRESS")
                .containsEntry("observed", "1")
                .containsEntry("limit", "1");
    }
}
