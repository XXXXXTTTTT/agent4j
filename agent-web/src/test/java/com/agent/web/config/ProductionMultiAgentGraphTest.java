package com.agent.web.config;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.engine.StateGraph;
import com.agent.core.multiagent.AgentHandoffEvent;
import com.agent.core.multiagent.AgentHandoffExecutor;
import com.agent.core.multiagent.AgentCatalog;
import com.agent.core.orchestration.OrchestrationMode;
import com.agent.core.nodes.CoderNode;
import com.agent.core.nodes.OpsNode;
import com.agent.core.nodes.PlannerNode;
import com.agent.core.nodes.ReviewerNode;
import com.agent.web.orchestration.ProductionMultiAgentOrchestrator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionMultiAgentGraphTest {

    @Test
    void serialModeKeepsPlannerCoderOpsReviewerOrder() {
        try (ProductionMultiAgentOrchestrator orchestrator = orchestrator(List.of())) {
            assertThat(orchestrator.serialTopology()).containsExactly(
                    "planner", "coder", "ops", "reviewer");
        }
    }

    @Test
    void parallelResearchRunsReadOnlyChildrenAndMergesBeforeCoder() throws Exception {
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();
        GraphRegistry graphs = new GraphRegistry(Map.of(
                "multiagent-research-code", () -> graph("research.codeEvidence", "code"),
                "multiagent-research-tests", () -> graph("research.testEvidence", "tests")));
        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                ProductionMultiAgentOrchestrator.catalog(), graphs, events::add);
             ProductionMultiAgentOrchestrator orchestrator =
                     new ProductionMultiAgentOrchestrator(executor)) {
            AgentState state = AgentState.empty()
                    .withVariable(PlannerNode.PLAN_KEY, "plan")
                    .withVariable(CoderNode.WORKSPACE_PATH_KEY, "D:/workspace")
                    .withVariable(ProductionMultiAgentOrchestrator.MODEL_GROUP_KEY_PREFIX
                            + com.agent.core.orchestration.AgentRole.RESEARCHER.name(), "test")
                    .withVariable(ProductionMultiAgentOrchestrator.MODE_KEY,
                            OrchestrationMode.PARALLEL_RESEARCH.name());
            AgentState result = orchestrator.researchNode().execute(
                    new NodeExecutionContext(UUID.randomUUID(), "research"), state);
            assertThat(result.variables())
                    .containsEntry("research.codeEvidence", "code")
                    .containsEntry("research.testEvidence", "tests")
                    .containsKey(ProductionMultiAgentOrchestrator.RESEARCH_SUMMARY_KEY);
            assertThat(events.stream().filter(event -> event instanceof AgentHandoffEvent.Started)
                    .map(AgentHandoffEvent::toAgent).toList())
                    .containsExactlyInAnyOrder("researcher-code", "researcher-tests");
            assertThat(ProductionMultiAgentOrchestrator.catalog().require("researcher-code")
                    .ownedStateKeys()).doesNotContain(CoderNode.WORKSPACE_PATH_KEY);
        }
    }

    @Test
    void reviewLoopUsesFreshVerifierContext() throws Exception {
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();
        GraphRegistry graphs = new GraphRegistry(Map.of(
                "multiagent-verifier", () -> {
                    StateGraph graph = new StateGraph(2);
                    graph.addNode("verify", child -> child
                            .withVariable(ReviewerNode.APPROVED_KEY, "true")
                            .withVariable(ReviewerNode.SUMMARY_KEY, "verified")
                            .withVariable(ReviewerNode.FEEDBACK_KEY, ""));
                    graph.setEntryPoint("verify").addEdge("verify", StateGraph.END);
                    return graph;
                }));
        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                ProductionMultiAgentOrchestrator.catalog(), graphs, events::add);
             ProductionMultiAgentOrchestrator orchestrator =
                     new ProductionMultiAgentOrchestrator(executor)) {
            AgentState state = AgentState.empty()
                    .withVariable(CoderNode.UNIFIED_DIFF_KEY, "diff")
                    .withVariable(OpsNode.STDOUT_KEY, "ok")
                    .withVariable(OpsNode.STDERR_KEY, "")
                    .withVariable(ProductionMultiAgentOrchestrator.MODEL_GROUP_KEY_PREFIX
                            + com.agent.core.orchestration.AgentRole.VERIFIER.name(), "test")
                    .withVariable(ProductionMultiAgentOrchestrator.MODE_KEY,
                            OrchestrationMode.REVIEW_LOOP.name());
            AgentState result = orchestrator.reviewNode(child -> child)
                    .execute(new NodeExecutionContext(UUID.randomUUID(), "reviewer"), state);
            assertThat(result.variables()).containsEntry(ReviewerNode.APPROVED_KEY, "true");
            assertThat(orchestrator.lastReviewContextMode()).isEqualTo(
                    com.agent.core.multiagent.HandoffContextMode.FRESH);
        }
    }

    private ProductionMultiAgentOrchestrator orchestrator(List<AgentHandoffEvent> events) {
        return new ProductionMultiAgentOrchestrator(new AgentHandoffExecutor(
                ProductionMultiAgentOrchestrator.catalog(),
                new GraphRegistry(Map.of("unused", () -> graph("unused", "unused"))),
                events::add));
    }

    private static StateGraph graph(String key, String value) {
        StateGraph graph = new StateGraph(2);
        graph.addNode("worker", state -> state.withVariable(key, value));
        graph.setEntryPoint("worker").addEdge("worker", StateGraph.END);
        return graph;
    }
}
