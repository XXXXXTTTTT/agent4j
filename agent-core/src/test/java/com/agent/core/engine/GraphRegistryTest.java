package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRegistryTest {

    @Test
    void createsIndependentGraphsForExactIdentifier() {
        AtomicInteger creations = new AtomicInteger();
        Map<String, GraphFactory> factories = new LinkedHashMap<>();
        factories.put("coder-ops-reviewer", () -> {
            creations.incrementAndGet();
            return new StateGraph(1);
        });
        GraphRegistry registry = new GraphRegistry(factories);
        factories.clear();

        StateGraph first = registry.create("coder-ops-reviewer");
        StateGraph second = registry.create("coder-ops-reviewer");
        try (first; second) {
            assertThat(first).isNotSameAs(second);
            assertThat(creations).hasValue(2);
        }
    }

    @Test
    void rejectsUnknownIdentifierWithoutCaseConversion() {
        GraphRegistry registry = new GraphRegistry(
                Map.of("coder-ops-reviewer", () -> new StateGraph(1)));

        assertThatThrownBy(() -> registry.create("missing"))
                .isInstanceOfSatisfying(GraphNotFoundException.class, exception ->
                        assertThat(exception.graphId()).isEqualTo("missing"));
        assertThatThrownBy(() -> registry.create("CODER-OPS-REVIEWER"))
                .isInstanceOfSatisfying(GraphNotFoundException.class, exception ->
                        assertThat(exception.graphId()).isEqualTo("CODER-OPS-REVIEWER"));
    }

    @Test
    void validatesFactoriesAndFactoryResults() {
        assertThatThrownBy(() -> new GraphRegistry(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphRegistry(null))
                .isInstanceOf(NullPointerException.class);

        Map<String, GraphFactory> blankKey = new LinkedHashMap<>();
        blankKey.put(" ", () -> new StateGraph(1));
        assertThatThrownBy(() -> new GraphRegistry(blankKey))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, GraphFactory> nullFactory = new LinkedHashMap<>();
        nullFactory.put("graph", null);
        assertThatThrownBy(() -> new GraphRegistry(nullFactory))
                .isInstanceOf(NullPointerException.class);

        GraphRegistry nullResult = new GraphRegistry(Map.of("graph", () -> null));
        assertThatThrownBy(() -> nullResult.create("graph"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> nullResult.create(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
