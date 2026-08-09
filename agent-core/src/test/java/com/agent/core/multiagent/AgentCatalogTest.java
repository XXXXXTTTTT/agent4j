package com.agent.core.multiagent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCatalogTest {

    @Test
    void freezesDefinitionsAndRequiresExactAgentId() {
        List<AgentDescriptor> descriptors = new ArrayList<>(List.of(
                descriptor("planner", Set.of("worker")),
                descriptor("worker", Set.of())));

        AgentCatalog catalog = new AgentCatalog(descriptors);
        descriptors.clear();

        assertThat(catalog.list()).extracting(AgentDescriptor::agentId)
                .containsExactly("planner", "worker");
        assertThat(catalog.require("worker").graphId()).isEqualTo("worker-graph");
        assertThatThrownBy(() -> catalog.require("WORKER"))
                .isInstanceOf(AgentNotFoundException.class)
                .hasMessageContaining("WORKER");
        assertThatThrownBy(() -> catalog.list().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateUnknownAndSelfTargets() {
        assertThatThrownBy(() -> new AgentCatalog(List.of(
                descriptor("planner", Set.of()),
                descriptor("planner", Set.of()))))
                .isInstanceOf(AgentDescriptorException.class);

        assertThatThrownBy(() -> new AgentCatalog(List.of(
                descriptor("planner", Set.of("missing")))))
                .isInstanceOf(AgentDescriptorException.class)
                .hasMessageContaining("missing");

        assertThatThrownBy(() -> new AgentCatalog(List.of(
                descriptor("planner", Set.of("planner")))))
                .isInstanceOf(AgentDescriptorException.class)
                .hasMessageContaining("planner");
    }

    @Test
    void rejectsOverlappingReadableAndOwnedKeys() {
        assertThatThrownBy(() -> new AgentDescriptor(
                "worker",
                "worker-graph",
                Set.of("workspacePath"),
                Set.of("workspacePath"),
                Set.of()))
                .isInstanceOf(AgentDescriptorException.class)
                .hasMessageContaining("workspacePath");
    }

    private AgentDescriptor descriptor(String id, Set<String> targets) {
        return new AgentDescriptor(
                id,
                id + "-graph",
                Set.of("workspacePath"),
                Set.of(id + ".result"),
                targets);
    }
}
