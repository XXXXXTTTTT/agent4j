package com.agent.core.engine;

import com.agent.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubgraphNodeTest {

    @Test
    void bridgesIndependentStateAndKeepsParentRunId() {
        AtomicReference<UUID> childRunId = new AtomicReference<>();
        AtomicBoolean childVirtual = new AtomicBoolean();
        AtomicInteger graphCreations = new AtomicInteger();
        GraphFactory factory = () -> {
            graphCreations.incrementAndGet();
            StateGraph graph = new StateGraph(3);
            graph.addNode("search", new Node() {
                        @Override
                        public AgentState execute(NodeExecutionContext context, AgentState state) {
                            childRunId.set(context.runId());
                            childVirtual.set(Thread.currentThread().isVirtual());
                            NodeExecutionContext.progress("查询完成");
                            return state.withVariable("summary", "done");
                        }

                        @Override
                        public AgentState execute(AgentState state) {
                            return state.withVariable("summary", "done");
                        }
                    })
                    .addNode("summarize", state -> state.withVariable("private.child", "hidden"))
                    .addEdge("search", "summarize")
                    .addEdge("summarize", StateGraph.END)
                    .setEntryPoint("search");
            return graph;
        };
        SubgraphStateBridge bridge = new SubgraphStateBridge() {
            @Override
            public AgentState project(AgentState parent) {
                return AgentState.empty()
                        .withVariable("query", parent.variables().get("task"));
            }

            @Override
            public AgentState merge(AgentState parent, AgentState child) {
                return parent.withVariable("report", child.variables().get("summary"));
            }
        };
        List<String> summaries = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();

        try (StateGraph parent = new StateGraph(3)) {
            parent.addNode("research", new SubgraphNode("research", factory, bridge))
                    .addEdge("research", StateGraph.END)
                    .setEntryPoint("research");
            GraphExecutionResult result = parent.execute(
                    new GraphExecutionRequest(
                            runId,
                            new AgentState(
                                    List.of(ChatMessage.user("用户问题")),
                                    Map.of("task", "java"),
                                    List.of("parent-trace")),
                            parent.entryPoint(),
                            false),
                    new GraphExecutionListener() {
                        @Override
                        public void onNodeStarted(String nodeName, AgentState state) {
                        }

                        @Override
                        public void onNodeProgress(String nodeName, String summary) {
                            summaries.add(summary);
                        }

                        @Override
                        public void onNodeCompleted(
                                String nodeName,
                                String nextNode,
                                AgentState state) {
                        }
                    });

            AgentState state = ((GraphExecutionResult.Completed) result).state();
            assertThat(childRunId).hasValue(runId);
            assertThat(childVirtual).isTrue();
            assertThat(graphCreations).hasValue(1);
            assertThat(state.variables()).containsEntry("report", "done")
                    .containsEntry("task", "java")
                    .doesNotContainKey("query")
                    .doesNotContainKey("private.child");
            assertThat(state.messages()).containsExactly(ChatMessage.user("用户问题"));
            assertThat(state.trace()).containsExactly("parent-trace");
            assertThat(summaries).containsExactly(
                    "subgraph:research:started",
                    "subgraph:research:node:search:started",
                    "subgraph:research:node:search:查询完成",
                    "subgraph:research:node:search:completed:summarize",
                    "subgraph:research:node:summarize:started",
                    "subgraph:research:node:summarize:completed:__END__",
                    "subgraph:research:completed");
        }
    }

    @Test
    void rejectsInvalidSubgraphBeforeAnyChildNodeRuns() {
        AtomicBoolean childNodeExecuted = new AtomicBoolean();
        GraphFactory invalidFactory = () -> {
            StateGraph graph = new StateGraph(3);
            graph.addNode("start", state -> {
                        childNodeExecuted.set(true);
                        return state;
                    })
                    .addNode("orphan", state -> state)
                    .addEdge("start", StateGraph.END)
                    .setEntryPoint("start");
            return graph;
        };
        SubgraphNode node = new SubgraphNode(
                "invalid",
                invalidFactory,
                new SubgraphStateBridge() {
                    @Override
                    public AgentState project(AgentState parent) {
                        return AgentState.empty();
                    }

                    @Override
                    public AgentState merge(AgentState parent, AgentState child) {
                        return parent;
                    }
                });

        assertThatThrownBy(() -> node.execute(
                new NodeExecutionContext(UUID.randomUUID(), "parent"),
                AgentState.empty()))
                .isInstanceOf(GraphTopologyException.class);
        assertThat(childNodeExecuted).isFalse();
    }

    @Test
    void preservesChildFailureCauseAndSubgraphId() {
        IOException failure = new IOException("child unavailable");
        GraphFactory factory = () -> {
            StateGraph graph = new StateGraph(2);
            graph.addNode("child", state -> {
                        throw failure;
                    })
                    .addEdge("child", StateGraph.END)
                    .setEntryPoint("child");
            return graph;
        };
        SubgraphNode node = new SubgraphNode("failing", factory, identityBridge());

        assertThatThrownBy(() -> node.execute(
                new NodeExecutionContext(UUID.randomUUID(), "parent"),
                AgentState.empty()))
                .isInstanceOfSatisfying(SubgraphExecutionException.class, exception -> {
                    assertThat(exception.subgraphId()).isEqualTo("failing");
                    assertThat(exception.getCause()).isInstanceOf(GraphExecutionException.class);
                    assertThat(exception.getCause().getCause()).isSameAs(failure);
                });
    }

    @Test
    void rejectsNestedInterruptWithExactRequest() {
        InterruptRequest request = new InterruptRequest(
                UUID.randomUUID(), "risky", "需要审批", Map.of("command", "write"));
        GraphFactory factory = () -> {
            StateGraph graph = new StateGraph(
                    2,
                    (runId, nodeName, state) -> java.util.Optional.of(request));
            graph.addNode("risky", state -> state)
                    .addEdge("risky", StateGraph.END)
                    .setEntryPoint("risky");
            return graph;
        };
        SubgraphNode node = new SubgraphNode("approval", factory, identityBridge());

        assertThatThrownBy(() -> node.execute(
                new NodeExecutionContext(UUID.randomUUID(), "parent"),
                AgentState.empty()))
                .isInstanceOfSatisfying(SubgraphInterruptedException.class, exception -> {
                    assertThat(exception.subgraphId()).isEqualTo("approval");
                    assertThat(exception.nodeName()).isEqualTo("risky");
                    assertThat(exception.request()).isEqualTo(request);
                });
    }

    @Test
    void requiresNonNullConstructionArguments() {
        assertThatThrownBy(() -> new SubgraphNode(" ", () -> new StateGraph(1), identityBridge()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubgraphNode("id", null, identityBridge()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SubgraphNode("id", () -> new StateGraph(1), null))
                .isInstanceOf(NullPointerException.class);
    }

    private SubgraphStateBridge identityBridge() {
        return new SubgraphStateBridge() {
            @Override
            public AgentState project(AgentState parent) {
                return parent;
            }

            @Override
            public AgentState merge(AgentState parent, AgentState child) {
                return child;
            }
        };
    }
}
