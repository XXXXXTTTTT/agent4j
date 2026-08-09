package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateGraphTopologyTest {

    @Test
    void validatesConditionalCycleWithEndRoute() {
        try (StateGraph graph = new StateGraph(5)) {
            graph.addNode("agent", state -> state)
                    .addNode("tool", state -> state)
                    .addConditionalEdges(
                            "agent",
                            state -> "end",
                            Map.of("tool", "tool", "end", StateGraph.END))
                    .addEdge("tool", "agent")
                    .setEntryPoint("agent");

            GraphTopology topology = graph.validateTopology();

            assertThat(topology.valid()).isTrue();
            assertThat(topology.cyclicNodes())
                    .containsExactlyInAnyOrder("agent", "tool");
            assertThat(topology.outgoingTargets().get("agent"))
                    .containsExactlyInAnyOrder("tool", StateGraph.END);
        }
    }

    @Test
    void inspectReturnsInvalidEvidenceWhileStrictValidationThrows() {
        try (StateGraph graph = new StateGraph(3)) {
            graph.addNode("start", state -> state)
                    .addNode("dead", state -> state)
                    .addNode("loop", state -> state)
                    .addEdge("start", StateGraph.END)
                    .addEdge("loop", "loop")
                    .setEntryPoint("start");

            GraphTopology topology = graph.inspectTopology();

            assertThat(topology.valid()).isFalse();
            assertThat(topology.unreachableNodes()).containsExactlyInAnyOrder("dead", "loop");
            assertThat(topology.deadEndNodes()).containsExactly("dead");
            assertThat(topology.nodesWithoutEndPath()).containsExactlyInAnyOrder("dead", "loop");
            assertThatThrownBy(graph::validateTopology)
                    .isInstanceOfSatisfying(GraphTopologyException.class, exception ->
                            assertThat(exception.topology().unreachableNodes())
                                    .containsExactlyInAnyOrder("dead", "loop"));
        }
    }

    @Test
    void topologySnapshotDoesNotChangeWhenBuilderChanges() {
        try (StateGraph graph = new StateGraph(3)) {
            graph.addNode("start", state -> state)
                    .addEdge("start", StateGraph.END)
                    .setEntryPoint("start");
            GraphTopology before = graph.inspectTopology();

            graph.addNode("later", state -> state);
            GraphTopology after = graph.inspectTopology();

            assertThat(before.nodeNames()).containsExactly("start");
            assertThat(before.valid()).isTrue();
            assertThat(after.nodeNames()).containsExactly("start", "later");
            assertThat(after.unreachableNodes()).containsExactly("later");
            assertThat(after.deadEndNodes()).containsExactly("later");
        }
    }

    @Test
    void requiresEntryPointAndOpenGraph() {
        StateGraph graph = new StateGraph(2);
        graph.addNode("start", state -> state)
                .addEdge("start", StateGraph.END);

        assertThatThrownBy(graph::inspectTopology)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("入口");

        graph.setEntryPoint("start");
        graph.close();
        assertThatThrownBy(graph::inspectTopology)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关闭");
    }
}
