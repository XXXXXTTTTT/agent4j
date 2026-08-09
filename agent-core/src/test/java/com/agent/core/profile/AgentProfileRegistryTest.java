package com.agent.core.profile;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.GraphNotFoundException;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.TaskType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentProfileRegistryTest {

    private static final ExecutionBudget BUDGET = new ExecutionBudget(
            Duration.ofSeconds(30), Duration.ofSeconds(5), 1_000, 4, 2);

    @Test
    void validatesAndFreezesProfileMetadata() {
        AgentProfile profile = profile("profile-a", "graph-a");

        assertThat(profile.profileId()).isEqualTo("profile-a");
        assertThat(profile.graphId()).isEqualTo("graph-a");
        assertThat(profile.taskTypes()).containsExactly(TaskType.CODE);
        assertThat(profile.capabilities()).containsExactly("ast");
        assertThatThrownBy(() -> profile.taskTypes().add(TaskType.VISION))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> profile.capabilities().add("sandbox"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void findsExactProfilesAndInspectsTopologyWithoutExecutingNodes() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger nodeCalls = new AtomicInteger();
        GraphRegistry graphs = new GraphRegistry(Map.of(
                "graph-a", () -> {
                    factoryCalls.incrementAndGet();
                    return new StateGraph(1)
                            .addNode("done", (AgentState state) -> {
                                nodeCalls.incrementAndGet();
                                return state;
                            })
                            .setEntryPoint("done")
                            .addEdge("done", StateGraph.END);
                }));
        AgentProfileRegistry registry = new AgentProfileRegistry(
                Map.of(
                        "profile-a", profile("profile-a", "graph-a"),
                        "profile-b", profile("profile-b", "graph-a")), graphs);

        assertThat(registry.profileIds()).containsExactly("profile-a", "profile-b");
        assertThat(registry.get("profile-a")).isEqualTo(profile("profile-a", "graph-a"));
        AgentProfileSnapshot snapshot = registry.inspect("profile-a");

        assertThat(snapshot.profile()).isEqualTo(profile("profile-a", "graph-a"));
        assertThat(snapshot.topology().entryPoint()).isEqualTo("done");
        assertThat(snapshot.topology().nodeNames()).containsExactly("done");
        assertThat(factoryCalls).hasValue(1);
        assertThat(nodeCalls).hasValue(0);
    }

    @Test
    void rejectsUnknownProfileAndPropagatesUnknownGraph() {
        GraphRegistry graphs = new GraphRegistry(Map.of("graph-a", () -> new StateGraph(1)
                .addNode("done", state -> state)
                .setEntryPoint("done")
                .addEdge("done", StateGraph.END)));
        AgentProfileRegistry registry = new AgentProfileRegistry(
                Map.of("profile-a", profile("profile-a", "graph-a")), graphs);

        assertThatThrownBy(() -> registry.get("profile-b"))
                .isInstanceOf(AgentProfileNotFoundException.class)
                .hasMessage("Agent Profile 未注册: profile-b");

        AgentProfileRegistry invalidGraph = new AgentProfileRegistry(
                Map.of("profile-b", profile("profile-b", "missing-graph")), graphs);
        assertThatThrownBy(() -> invalidGraph.inspect("profile-b"))
                .isInstanceOf(GraphNotFoundException.class)
                .hasMessage("图未注册: missing-graph");
    }

    private AgentProfile profile(String profileId, String graphId) {
        return new AgentProfile(
                profileId,
                graphId,
                "Profile " + profileId,
                "测试 Profile",
                Set.of(TaskType.CODE),
                Set.of("ast"),
                BUDGET);
    }
}
