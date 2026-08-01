package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateGraphInterruptTest {

    private static final UUID RUN_ID = UUID.fromString("487fcb85-153f-41bb-989a-355e67a4a38c");
    private static final UUID INTERRUPT_ID = UUID.fromString("afddde73-dc74-4135-b46e-95047f5ae9b0");

    @Test
    void interruptsBeforeNodeWithoutExecutingNodeOrListener() {
        AtomicBoolean guardedExecuted = new AtomicBoolean();
        List<String> events = new ArrayList<>();
        InterruptRequest request = interrupt("guarded");
        InterruptPolicy policy = (runId, nodeName, state) ->
                "guarded".equals(nodeName) ? Optional.of(request) : Optional.empty();

        try (StateGraph graph = new StateGraph(4, policy)) {
            graph.addNode("first", state -> state.withTraceEntry("first"))
                    .addNode("guarded", state -> {
                        guardedExecuted.set(true);
                        return state.withTraceEntry("guarded");
                    })
                    .addEdge("first", "guarded")
                    .addEdge("guarded", StateGraph.END)
                    .setEntryPoint("first");

            GraphExecutionResult result = graph.execute(
                    new GraphExecutionRequest(RUN_ID, AgentState.empty(), "first", false),
                    listener(events));

            assertThat(result).isEqualTo(new GraphExecutionResult.Interrupted(
                    AgentState.empty().withTraceEntry("first"), "guarded", request));
            assertThat(guardedExecuted).isFalse();
            assertThat(events).containsExactly(
                    "started:first",
                    "completed:first:guarded");
        }
    }

    @Test
    void bypassesOnlyTheStartingNodeOnce() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        InterruptRequest request = interrupt("guarded");
        InterruptPolicy policy = (runId, nodeName, state) -> {
            evaluations.incrementAndGet();
            return Optional.of(request);
        };

        try (StateGraph graph = new StateGraph(3, policy)) {
            graph.addNode("guarded", state -> {
                        executions.incrementAndGet();
                        return state.withTraceEntry("guarded");
                    })
                    .addEdge("guarded", "guarded")
                    .setEntryPoint("guarded");

            GraphExecutionResult result = graph.execute(
                    new GraphExecutionRequest(RUN_ID, AgentState.empty(), "guarded", true),
                    listener(new ArrayList<>()));

            assertThat(result).isEqualTo(new GraphExecutionResult.Interrupted(
                    AgentState.empty().withTraceEntry("guarded"), "guarded", request));
            assertThat(executions).hasValue(1);
            assertThat(evaluations).hasValue(1);
        }
    }

    @Test
    void resumesAtExactNodeAndReportsListenerOrder() {
        List<String> events = new ArrayList<>();

        try (StateGraph graph = new StateGraph(2, InterruptPolicy.never())) {
            graph.addNode("first", state -> state.withTraceEntry("first"))
                    .addNode("guarded", state -> state
                            .withVariable("virtual", Boolean.toString(Thread.currentThread().isVirtual()))
                            .withTraceEntry("guarded"))
                    .addEdge("first", "guarded")
                    .addEdge("guarded", StateGraph.END)
                    .setEntryPoint("first");

            GraphExecutionResult result = graph.execute(
                    new GraphExecutionRequest(RUN_ID, AgentState.empty(), "guarded", false),
                    listener(events));

            assertThat(result).isEqualTo(new GraphExecutionResult.Completed(
                    AgentState.empty()
                            .withVariable("virtual", "true")
                            .withTraceEntry("guarded")));
            assertThat(events).containsExactly(
                    "started:guarded",
                    "completed:guarded:" + StateGraph.END);
            assertThat(graph.entryPoint()).isEqualTo("first");
        }
    }

    @Test
    void resolvesConditionalEdgeBeforeCompletedListener() {
        List<String> events = new ArrayList<>();

        try (StateGraph graph = new StateGraph(2, InterruptPolicy.never())) {
            graph.addNode("route", state -> state.withVariable("route", "done"))
                    .addConditionalEdges(
                            "route",
                            state -> state.variables().get("route"),
                            Map.of("done", StateGraph.END))
                    .setEntryPoint("route");

            graph.execute(
                    new GraphExecutionRequest(RUN_ID, AgentState.empty(), "route", false),
                    listener(events));

            assertThat(events).containsExactly(
                    "started:route",
                    "completed:route:" + StateGraph.END);
        }
    }

    @Test
    void rejectsUnknownStartNodeAndMismatchedInterruptNode() {
        try (StateGraph graph = new StateGraph(
                2,
                (runId, nodeName, state) -> Optional.of(interrupt("other")))) {
            graph.addNode("guarded", state -> state)
                    .addEdge("guarded", StateGraph.END)
                    .setEntryPoint("guarded");

            assertThatThrownBy(() -> graph.execute(
                    new GraphExecutionRequest(RUN_ID, AgentState.empty(), "missing", false),
                    listener(new ArrayList<>())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
            assertThatThrownBy(() -> graph.execute(
                    new GraphExecutionRequest(RUN_ID, AgentState.empty(), "guarded", false),
                    listener(new ArrayList<>())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("other")
                    .hasMessageContaining("guarded");
        }
    }

    @Test
    void validatesExecutionProtocolModels() {
        InterruptRequest request = interrupt("guarded");

        assertThatThrownBy(() -> new GraphExecutionRequest(
                null, AgentState.empty(), "guarded", false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphExecutionRequest(
                RUN_ID, null, "guarded", false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphExecutionRequest(
                RUN_ID, AgentState.empty(), " ", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphExecutionResult.Completed(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphExecutionResult.Interrupted(
                AgentState.empty(), "guarded", interrupt("other")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new GraphExecutionResult.Interrupted(
                AgentState.empty(), "guarded", request).request()).isEqualTo(request);
    }

    private GraphExecutionListener listener(List<String> events) {
        return new GraphExecutionListener() {
            @Override
            public void onNodeStarted(String nodeName, AgentState state) {
                events.add("started:" + nodeName);
            }

            @Override
            public void onNodeCompleted(String nodeName, String nextNode, AgentState state) {
                events.add("completed:" + nodeName + ":" + nextNode);
            }
        };
    }

    private InterruptRequest interrupt(String nodeName) {
        return new InterruptRequest(
                INTERRUPT_ID,
                nodeName,
                "需要人工审批",
                Map.of("operation", "deploy"));
    }
}
