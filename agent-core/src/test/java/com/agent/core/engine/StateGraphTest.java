package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateGraphTest {

    @Test
    void rejectsInvalidNodeExecutionContext() {
        assertThatThrownBy(() -> new NodeExecutionContext(null, "work"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("runId");
        assertThatThrownBy(() -> new NodeExecutionContext(UUID.randomUUID(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeName");
    }

    @Test
    void passesExactRunContextToNodeOnVirtualThread() {
        UUID runId = UUID.randomUUID();
        AtomicReference<NodeExecutionContext> observedContext = new AtomicReference<>();
        AtomicBoolean virtualThread = new AtomicBoolean();
        Node node = new Node() {
            @Override
            public AgentState execute(AgentState state) {
                throw new AssertionError("不应调用无上下文入口");
            }

            @Override
            public AgentState execute(NodeExecutionContext context, AgentState state) {
                observedContext.set(context);
                virtualThread.set(Thread.currentThread().isVirtual());
                return state.withTraceEntry("work");
            }
        };

        try (StateGraph graph = new StateGraph(2)) {
            graph.addNode("work", node)
                    .addEdge("work", StateGraph.END)
                    .setEntryPoint("work");

            GraphExecutionResult result = graph.execute(
                    new GraphExecutionRequest(runId, AgentState.empty(), "work", false),
                    new GraphExecutionListener() {
                        @Override
                        public void onNodeStarted(String nodeName, AgentState state) {
                        }

                        @Override
                        public void onNodeCompleted(
                                String nodeName,
                                String nextNode,
                                AgentState state) {
                        }
                    });

            assertThat(result).isInstanceOf(GraphExecutionResult.Completed.class);
            assertThat(observedContext.get())
                    .isEqualTo(new NodeExecutionContext(runId, "work"));
            assertThat(virtualThread).isTrue();
        }
    }

    @Test
    void exposesCurrentContextInsideNodeVirtualThread() {
        UUID runId = UUID.randomUUID();
        AtomicReference<Optional<NodeExecutionContext>> currentContext =
                new AtomicReference<>();
        AtomicBoolean virtualThread = new AtomicBoolean();
        Node node = new Node() {
            @Override
            public AgentState execute(AgentState state) {
                throw new AssertionError("不应调用无上下文入口");
            }

            @Override
            public AgentState execute(NodeExecutionContext context, AgentState state) {
                currentContext.set(NodeExecutionContext.current());
                virtualThread.set(Thread.currentThread().isVirtual());
                return state;
            }
        };

        try (StateGraph graph = new StateGraph(1)) {
            graph.addNode("work", node)
                    .addEdge("work", StateGraph.END)
                    .setEntryPoint("work");

            graph.execute(new GraphExecutionRequest(runId, AgentState.empty(), "work", false),
                    noOpListener());
        }

        assertThat(currentContext.get())
                .hasValue(new NodeExecutionContext(runId, "work"));
        assertThat(virtualThread).isTrue();
        assertThat(NodeExecutionContext.current()).isEmpty();
    }

    @Test
    void clearsCurrentContextWhenNodeFails() {
        AtomicReference<Optional<NodeExecutionContext>> currentContext =
                new AtomicReference<>();
        RuntimeException failure = new RuntimeException("node failed");
        Node node = new Node() {
            @Override
            public AgentState execute(AgentState state) {
                throw new AssertionError("不应调用无上下文入口");
            }

            @Override
            public AgentState execute(NodeExecutionContext context, AgentState state) {
                currentContext.set(NodeExecutionContext.current());
                throw failure;
            }
        };

        try (StateGraph graph = new StateGraph(1)) {
            graph.addNode("work", node)
                    .addEdge("work", StateGraph.END)
                    .setEntryPoint("work");

            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .isInstanceOf(GraphExecutionException.class)
                    .hasCause(failure);
        }

        assertThat(currentContext.get()).isNotNull();
        assertThat(currentContext.get()).isPresent();
        assertThat(NodeExecutionContext.current()).isEmpty();
    }

    @Test
    void rejectsNestedContextBinding() {
        NodeExecutionContext outer = new NodeExecutionContext(UUID.randomUUID(), "outer");
        NodeExecutionContext inner = new NodeExecutionContext(UUID.randomUUID(), "inner");

        assertThatThrownBy(() -> NodeExecutionContext.callWithin(outer,
                () -> NodeExecutionContext.callWithin(inner, () -> "nested")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("嵌套");
        assertThat(NodeExecutionContext.current()).isEmpty();
    }

    private static GraphExecutionListener noOpListener() {
        return new GraphExecutionListener() {
            @Override
            public void onNodeStarted(String nodeName, AgentState state) {
            }

            @Override
            public void onNodeCompleted(
                    String nodeName,
                    String nextNode,
                    AgentState state) {
            }
        };
    }

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
