package com.agent.web.config;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphFactory;
import com.agent.core.engine.StateGraph;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 提供 Docker 快速体验使用的显式演示图。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "agent.sample.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SampleGraphConfiguration {

    /** 注册精确 graphId 为 sample 的最小可运行图。 */
    @Bean("sample")
    GraphFactory sampleGraph() {
        return () -> new StateGraph(1)
                .addNode("sample", (AgentState state) ->
                        state.withVariable("sample.status", "ready"))
                .setEntryPoint("sample")
                .addEdge("sample", StateGraph.END);
    }
}
