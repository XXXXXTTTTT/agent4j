package com.agent.core.multiagent;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 生产多 Agent 编排的 EGG：权限、上下文、预算和 Trace 必须保持受治理。 */
class GovernedMultiAgentEggTest {

    @Test
    void researcherCannotWriteWorkspaceState() {
        GraphRegistry graphs = new GraphRegistry(Map.of(
                "research-graph", () -> graph(state -> state
                        .withVariable("research.evidence", "read-only")
                        .withVariable("coder.workspacePath", "D:/mutated"))));
        AgentCatalog catalog = catalog(
                new AgentDescriptor("planner", "planner-graph", Set.of(), Set.of(), Set.of("researcher")),
                new AgentDescriptor("researcher", "research-graph", Set.of("workspace.path"),
                        Set.of("research.evidence"), Set.of()));

        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(catalog, graphs, ignored -> { })) {
            assertThatThrownBy(() -> executor.execute(
                    UUID.randomUUID(),
                    AgentState.empty().withVariable("workspace.path", "D:/workspace"),
                    handoff("planner", "researcher", HandoffContextMode.FORK,
                            Set.of("research.evidence")),
                    HandoffExecutionContext.root("planner", 2, 2)).join())
                    .hasRootCauseInstanceOf(AgentHandoffStateException.class);
        }
    }

    @Test
    void parallelResearchProducesDistinctChildrenAndAuditableTrace() {
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();
        GraphRegistry graphs = new GraphRegistry(Map.of(
                "research-code-graph", () -> graph(state -> state.withVariable("research.code", "ok")),
                "research-tests-graph", () -> graph(state -> state.withVariable("research.tests", "ok"))));
        AgentCatalog catalog = catalog(
                new AgentDescriptor("planner", "planner-graph", Set.of(), Set.of(),
                        Set.of("researcher-code", "researcher-tests")),
                new AgentDescriptor("researcher-code", "research-code-graph", Set.of(),
                        Set.of("research.code"), Set.of()),
                new AgentDescriptor("researcher-tests", "research-tests-graph", Set.of(),
                        Set.of("research.tests"), Set.of()));
        UUID parentRunId = UUID.randomUUID();

        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(catalog, graphs, events::add)) {
            var code = executor.execute(parentRunId, AgentState.empty(),
                    handoff("planner", "researcher-code", HandoffContextMode.FORK,
                            Set.of("research.code")),
                    HandoffExecutionContext.root("planner", 2, 2));
            var tests = executor.execute(parentRunId, AgentState.empty(),
                    handoff("planner", "researcher-tests", HandoffContextMode.FORK,
                            Set.of("research.tests")),
                    HandoffExecutionContext.root("planner", 2, 2));

            var codeResult = code.join();
            var testsResult = tests.join();
            assertThat(codeResult.mergedParentState().variables()).containsEntry("research.code", "ok");
            assertThat(testsResult.mergedParentState().variables()).containsEntry("research.tests", "ok");
            assertThat(codeResult.childRunId()).isNotEqualTo(testsResult.childRunId());
            assertThat(events).isNotEmpty().allSatisfy(event -> {
                assertThat(event.parentRunId()).isEqualTo(parentRunId);
                assertThat(event.childRunId()).isNotEqualTo(parentRunId);
            });
            assertThat(events).anyMatch(AgentHandoffEvent.Started.class::isInstance)
                    .anyMatch(AgentHandoffEvent.Completed.class::isInstance);
        }
    }

    @Test
    void freshReviewerSeesOnlyTheHandoffContext() {
        GraphRegistry graphs = new GraphRegistry(Map.of(
                "review-graph", () -> graph(state -> {
                    assertThat(state.messages()).containsExactly(ChatMessage.user("执行受治理子任务"));
                    assertThat(state.variables()).containsOnlyKeys("review.input");
                    return state.withVariable("review.verdict", "approved");
                })));
        AgentCatalog catalog = catalog(
                new AgentDescriptor("coder", "coder-graph", Set.of(), Set.of(), Set.of("reviewer")),
                new AgentDescriptor("reviewer", "review-graph", Set.of("review.input"),
                        Set.of("review.verdict"), Set.of()));

        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(catalog, graphs, ignored -> { })) {
            AgentHandoffResult result = executor.execute(
                    UUID.randomUUID(),
                    AgentState.empty()
                            .withMessage(ChatMessage.user("完整会话正文"))
                            .withVariable("review.input", "diff"),
                    handoff("coder", "reviewer", HandoffContextMode.FRESH,
                            Set.of("review.verdict")),
                    HandoffExecutionContext.root("coder", 2, 2)).join();
            assertThat(result.mergedParentState().variables()).containsEntry("review.verdict", "approved");
        }
    }

    @Test
    void depthBudgetRejectsNestedHandoffBeforeChildRun() {
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();
        GraphRegistry graphs = new GraphRegistry(Map.of("review-graph", () -> graph(
                state -> state.withVariable("review.verdict", "approved"))));
        AgentCatalog catalog = catalog(
                new AgentDescriptor("researcher", "researcher-graph", Set.of(), Set.of(), Set.of("reviewer")),
                new AgentDescriptor("reviewer", "review-graph", Set.of(), Set.of("review.verdict"), Set.of()));

        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(catalog, graphs, events::add)) {
            assertThatThrownBy(() -> executor.execute(
                    UUID.randomUUID(), AgentState.empty(),
                    handoff("researcher", "reviewer", HandoffContextMode.FRESH,
                            Set.of("review.verdict")),
                    new HandoffExecutionContext(1, 1, 1, List.of("planner", "researcher"))))
                    .isInstanceOf(AgentHandoffDeniedException.class);
            assertThat(events).isEmpty();
        }
    }

    private static AgentCatalog catalog(AgentDescriptor... descriptors) {
        return new AgentCatalog(List.of(descriptors));
    }

    private static StateGraph graph(java.util.function.UnaryOperator<AgentState> operation) {
        StateGraph graph = new StateGraph(3);
        graph.addNode("worker", operation::apply);
        graph.setEntryPoint("worker");
        graph.addEdge("worker", StateGraph.END);
        return graph;
    }

    private static AgentHandoff handoff(
            String fromAgent,
            String toAgent,
            HandoffContextMode contextMode,
            Set<String> outputKeys) {
        return new AgentHandoff(
                UUID.randomUUID(), fromAgent, toAgent, "执行受治理子任务",
                contextMode, outputKeys, Duration.ofSeconds(2));
    }
}
