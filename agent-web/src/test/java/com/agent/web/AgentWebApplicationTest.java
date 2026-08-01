package com.agent.web;

import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.StateGraph;
import com.agent.web.config.RunRecoveryListener;
import com.agent.web.controller.RunController;
import com.agent.web.trace.InMemoryTraceEventBus;
import com.agent.web.trace.RunTraceWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(
        classes = {
                AgentWebApplication.class,
                AgentWebApplicationTest.TestHarnessDependencies.class
        },
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
class AgentWebApplicationTest {

    @Autowired
    private ApplicationContext context;

    @MockBean
    private RunRecoveryListener recoveryListener;

    @Test
    void assemblesHarnessBeansWithoutRegisteringProductionGraphs() {
        assertThat(context.getBean(Checkpointer.class)).isNotNull();
        assertThat(context.getBean(GraphRegistry.class)).isNotNull();
        assertThat(context.getBean(InMemoryTraceEventBus.class)).isNotNull();
        assertThat(context.getBean(AgentRunService.class)).isNotNull();
        assertThat(context.getBean(RunController.class)).isNotNull();
        assertThat(context.getBean(RunTraceWebSocketHandler.class)).isNotNull();

        try (StateGraph graph = context.getBean(GraphRegistry.class).create("phase4-test")) {
            assertThat(graph.entryPoint()).isEqualTo("done");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestHarnessDependencies {

        @Bean
        DataSource testDataSource() {
            return mock(DataSource.class);
        }

        @Bean("phase4-test")
        GraphFactory phase4TestGraph() {
            return () -> new StateGraph(1)
                    .addNode("done", state -> state)
                    .setEntryPoint("done")
                    .addEdge("done", StateGraph.END);
        }
    }
}
