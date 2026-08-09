package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphTopologyTest {

    @Test
    void freezesNestedCollectionsAndDerivesValidity() {
        Set<String> nodes = new LinkedHashSet<>(Set.of("start"));
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        Set<String> targets = new LinkedHashSet<>(Set.of(StateGraph.END));
        outgoing.put("start", targets);

        GraphTopology topology = new GraphTopology(
                "start", nodes, outgoing,
                Set.of(), Set.of(), Set.of(), Set.of());
        nodes.add("later");
        targets.add("other");

        assertThat(topology.nodeNames()).containsExactly("start");
        assertThat(topology.outgoingTargets().get("start"))
                .containsExactly(StateGraph.END);
        assertThat(topology.valid()).isTrue();
        assertThatThrownBy(() -> topology.outgoingTargets().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> topology.outgoingTargets().get("start").clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void analyzesLinearAndConditionalCycleTopologies() {
        GraphTopology linear = GraphTopologyAnalyzer.analyze(
                "start",
                Set.of("start"),
                Map.of("start", Set.of(StateGraph.END)));
        GraphTopology cycle = GraphTopologyAnalyzer.analyze(
                "agent",
                Set.of("agent", "tool"),
                Map.of(
                        "agent", Set.of("tool", StateGraph.END),
                        "tool", Set.of("agent")));

        assertThat(linear.valid()).isTrue();
        assertThat(linear.cyclicNodes()).isEmpty();
        assertThat(cycle.valid()).isTrue();
        assertThat(cycle.cyclicNodes()).containsExactlyInAnyOrder("agent", "tool");
        assertThat(cycle.nodesWithoutEndPath()).isEmpty();
    }

    @Test
    void detectsSelfCycleUnreachableDeadEndAndNoEndPath() {
        Set<String> nodes = new LinkedHashSet<>(Set.of("start", "dead", "orphan", "loop"));
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        outgoing.put("start", Set.of(StateGraph.END));
        outgoing.put("dead", Set.of());
        outgoing.put("orphan", Set.of(StateGraph.END));
        outgoing.put("loop", Set.of("loop"));

        GraphTopology topology = GraphTopologyAnalyzer.analyze("start", nodes, outgoing);

        assertThat(topology.valid()).isFalse();
        assertThat(topology.unreachableNodes())
                .containsExactlyInAnyOrder("dead", "orphan", "loop");
        assertThat(topology.deadEndNodes()).containsExactly("dead");
        assertThat(topology.nodesWithoutEndPath()).containsExactlyInAnyOrder("dead", "loop");
        assertThat(topology.cyclicNodes()).containsExactly("loop");
    }

    @Test
    void topologyExceptionRetainsStructuredSnapshot() {
        GraphTopology topology = GraphTopologyAnalyzer.analyze(
                "loop", Set.of("loop"), Map.of("loop", Set.of("loop")));

        GraphTopologyException exception = new GraphTopologyException(topology);

        assertThat(exception.topology()).isSameAs(topology);
        assertThat(exception).hasMessageContaining("nodesWithoutEndPath");
    }
}
