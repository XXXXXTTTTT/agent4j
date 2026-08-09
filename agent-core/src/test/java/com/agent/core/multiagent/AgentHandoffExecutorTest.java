package com.agent.core.multiagent;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.StateGraph;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentHandoffExecutorTest {

    @Test
    void executesIndependentVirtualThreadSubrunAndPublishesTrace() {
        AtomicBoolean virtualThread = new AtomicBoolean();
        GraphRegistry graphs = new GraphRegistry(Map.of("worker-graph", () -> {
            StateGraph graph = new StateGraph(3);
            graph.addNode("worker", state -> {
                virtualThread.set(Thread.currentThread().isVirtual());
                com.agent.core.engine.NodeExecutionContext.progress("正在处理子任务");
                return state.withVariable("worker.result", "done");
            });
            graph.setEntryPoint("worker");
            graph.addEdge("worker", StateGraph.END);
            return graph;
        }));
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();
        UUID parentRunId = UUID.randomUUID();
        AgentHandoff handoff = handoff(Duration.ofSeconds(2));

        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                catalog(Set.of("worker")), graphs, events::add)) {
            AgentHandoffResult result = executor.execute(
                    parentRunId,
                    parentState(),
                    handoff,
                    HandoffExecutionContext.root("planner", 2, 2)).join();

            assertThat(result.parentRunId()).isEqualTo(parentRunId);
            assertThat(result.childRunId()).isNotEqualTo(parentRunId);
            assertThat(result.mergedParentState().variables())
                    .containsEntry("worker.result", "done");
            assertThat(result.childContext().visitedAgents())
                    .containsExactly("planner", "worker");
            assertThat(virtualThread).isTrue();
            assertThat(events).anyMatch(AgentHandoffEvent.Started.class::isInstance)
                    .anyMatch(AgentHandoffEvent.NodeStarted.class::isInstance)
                    .anyMatch(AgentHandoffEvent.NodeProgress.class::isInstance)
                    .anyMatch(AgentHandoffEvent.NodeCompleted.class::isInstance)
                    .anyMatch(AgentHandoffEvent.Completed.class::isInstance)
                    .noneMatch(AgentHandoffEvent.Failed.class::isInstance);
            assertThat(events).allSatisfy(event -> {
                assertThat(event.taskId()).isEqualTo(handoff.taskId());
                assertThat(event.parentRunId()).isEqualTo(parentRunId);
                assertThat(event.childRunId()).isEqualTo(result.childRunId());
                assertThat(event.fromAgent()).isEqualTo("planner");
                assertThat(event.toAgent()).isEqualTo("worker");
            });
        }
    }

    @Test
    void cancelsTimedOutSubrunAndPublishesFailure() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        GraphRegistry graphs = new GraphRegistry(Map.of("worker-graph", () -> {
            StateGraph graph = new StateGraph(3);
            graph.addNode("worker", state -> {
                started.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw exception;
                }
                return state.withVariable("worker.result", "late");
            });
            graph.setEntryPoint("worker");
            graph.addEdge("worker", StateGraph.END);
            return graph;
        }));
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();

        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                catalog(Set.of("worker")), graphs, events::add)) {
            var future = executor.execute(
                    UUID.randomUUID(),
                    parentState(),
                    handoff(Duration.ofMillis(100)),
                    HandoffExecutionContext.root("planner", 2, 2));

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(future::join)
                    .hasRootCauseInstanceOf(AgentHandoffTimeoutException.class);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(events).anyMatch(AgentHandoffEvent.Failed.class::isInstance)
                    .noneMatch(AgentHandoffEvent.Completed.class::isInstance);
        }
    }

    @Test
    void cancelingReturnedFutureInterruptsChildSubrun() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        GraphRegistry graphs = new GraphRegistry(Map.of("worker-graph", () -> {
            StateGraph graph = new StateGraph(3);
            graph.addNode("worker", state -> {
                started.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw exception;
                }
                return state.withVariable("worker.result", "late");
            });
            graph.setEntryPoint("worker");
            graph.addEdge("worker", StateGraph.END);
            return graph;
        }));
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();

        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                catalog(Set.of("worker")), graphs, events::add)) {
            var future = executor.execute(
                    UUID.randomUUID(),
                    parentState(),
                    handoff(Duration.ofSeconds(2)),
                    HandoffExecutionContext.root("planner", 2, 2));

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(future.cancel(true)).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsTargetOutsideSourceWhitelistBeforeCreatingGraph() {
        AtomicBoolean graphCreated = new AtomicBoolean();
        GraphRegistry graphs = new GraphRegistry(Map.of("worker-graph", () -> {
            graphCreated.set(true);
            return completedGraph();
        }));

        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                catalog(Set.of()), graphs, ignored -> { })) {
            assertThatThrownBy(() -> executor.execute(
                    UUID.randomUUID(),
                    parentState(),
                    handoff(Duration.ofSeconds(1)),
                    HandoffExecutionContext.root("planner", 2, 2)))
                    .isInstanceOf(AgentHandoffDeniedException.class);
            assertThat(graphCreated).isFalse();
        }
    }

    @Test
    void rejectsNestedHitlInsteadOfReportingCompletion() {
        GraphRegistry graphs = new GraphRegistry(Map.of("worker-graph", () -> {
            StateGraph graph = new StateGraph(
                    3,
                    (runId, nodeName, state) -> Optional.of(new InterruptRequest(
                            UUID.randomUUID(), nodeName, "需要审批", Map.of())));
            graph.addNode("worker", state -> state.withVariable("worker.result", "done"));
            graph.setEntryPoint("worker");
            graph.addEdge("worker", StateGraph.END);
            return graph;
        }));
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();

        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                catalog(Set.of("worker")), graphs, events::add)) {
            assertThatThrownBy(() -> executor.execute(
                    UUID.randomUUID(),
                    parentState(),
                    handoff(Duration.ofSeconds(1)),
                    HandoffExecutionContext.root("planner", 2, 2)).join())
                    .hasRootCauseInstanceOf(AgentHandoffInterruptedException.class);
            assertThat(events).anyMatch(AgentHandoffEvent.Failed.class::isInstance)
                    .noneMatch(AgentHandoffEvent.Completed.class::isInstance);
        }
    }

    private AgentCatalog catalog(Set<String> targets) {
        return new AgentCatalog(List.of(
                new AgentDescriptor(
                        "planner", "planner-graph", Set.of(), Set.of("planner.result"), targets),
                new AgentDescriptor(
                        "worker",
                        "worker-graph",
                        Set.of("workspacePath"),
                        Set.of("worker.result"),
                        Set.of())));
    }

    private AgentState parentState() {
        return AgentState.empty().withVariable("workspacePath", "D:/workspace");
    }

    private AgentHandoff handoff(Duration timeout) {
        return new AgentHandoff(
                UUID.randomUUID(),
                "planner",
                "worker",
                "执行 worker 子任务",
                HandoffContextMode.FRESH,
                Set.of("worker.result"),
                timeout);
    }

    private StateGraph completedGraph() {
        StateGraph graph = new StateGraph(3);
        graph.addNode("worker", state -> state.withVariable("worker.result", "done"));
        graph.setEntryPoint("worker");
        graph.addEdge("worker", StateGraph.END);
        return graph;
    }
}
