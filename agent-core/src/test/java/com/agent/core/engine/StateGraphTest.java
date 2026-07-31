package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateGraphTest {

    @Test
    void executesPlannerToolFlowOnVirtualThreads() {
        try (StateGraph graph = new StateGraph(4)) {
            graph.addNode("planner", state -> state
                            .withVariable("action", "tool")
                            .withVariable("plannerVirtual", Boolean.toString(Thread.currentThread().isVirtual()))
                            .withTraceEntry("planner"))
                    .addNode("tool", state -> state
                            .withVariable("result", "42")
                            .withVariable("toolVirtual", Boolean.toString(Thread.currentThread().isVirtual()))
                            .withTraceEntry("tool"))
                    .addConditionalEdges(
                            "planner",
                            state -> state.variables().get("action"),
                            Map.of("tool", "tool"))
                    .addEdge("tool", StateGraph.END)
                    .setEntryPoint("planner");

            AgentState result = graph.execute(AgentState.empty());

            assertThat(result.variables())
                    .containsEntry("result", "42")
                    .containsEntry("plannerVirtual", "true")
                    .containsEntry("toolVirtual", "true");
            assertThat(result.trace()).containsExactly("planner", "tool");
        }
    }

    @Test
    void stopsLoopAtMaximumSteps() {
        try (StateGraph graph = new StateGraph(2)) {
            graph.addNode("loop", state -> state.withTraceEntry("loop"))
                    .addEdge("loop", "loop")
                    .setEntryPoint("loop");

            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .isInstanceOfSatisfying(MaxStepsExceededException.class, exception ->
                            assertThat(exception.maxSteps()).isEqualTo(2));
        }
    }

    @Test
    void rejectsUnknownConditionalRoute() {
        try (StateGraph graph = new StateGraph(2)) {
            graph.addNode("planner", state -> state)
                    .addConditionalEdges("planner", state -> "missing", Map.of("known", StateGraph.END))
                    .setEntryPoint("planner");

            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Test
    void preservesNodeFailureCause() {
        IOException failure = new IOException("tool unavailable");

        try (StateGraph graph = new StateGraph(2)) {
            graph.addNode("tool", state -> {
                        throw failure;
                    })
                    .addEdge("tool", StateGraph.END)
                    .setEntryPoint("tool");

            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .isInstanceOfSatisfying(GraphExecutionException.class, exception -> {
                        assertThat(exception.nodeName()).isEqualTo("tool");
                        assertThat(exception.getCause()).isSameAs(failure);
                    });
        }
    }

    @Test
    void rejectsExecutionAfterClose() {
        StateGraph graph = new StateGraph(1);
        graph.addNode("end", state -> state)
                .addEdge("end", StateGraph.END)
                .setEntryPoint("end");
        graph.close();

        assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关闭");
    }
}
