package com.agent.web.config;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SampleGraphConfigurationTest {

    @Test
    void createsTheSampleGraphUsedByTheContainerQuickStart() {
        GraphFactory factory = new SampleGraphConfiguration().sampleGraph();

        try (StateGraph graph = factory.create()) {
            assertThat(graph.entryPoint()).isEqualTo("sample");
            assertThat(graph.execute(AgentState.empty()).variables())
                    .containsEntry("sample.status", "ready");
        }
    }
}
