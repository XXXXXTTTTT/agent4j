package com.agent.eval;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.StateGraph;
import com.agent.core.llm.ChatMessage;
import com.agent.core.multiagent.AgentCatalog;
import com.agent.core.multiagent.AgentDescriptor;
import com.agent.core.multiagent.AgentHandoff;
import com.agent.core.multiagent.AgentHandoffDeniedException;
import com.agent.core.multiagent.AgentHandoffEvent;
import com.agent.core.multiagent.AgentHandoffExecutor;
import com.agent.core.multiagent.AgentHandoffResult;
import com.agent.core.multiagent.AgentHandoffStateException;
import com.agent.core.multiagent.AgentHandoffTimeoutException;
import com.agent.core.multiagent.AgentStateMergeException;
import com.agent.core.multiagent.HandoffContextMode;
import com.agent.core.multiagent.HandoffExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 对受治理 Multi-Agent Handoff 执行确定性端到端评测。 */
class MultiAgentHandoffEddTest {

    private static final Set<String> REPORT_FIELDS = Set.of(
            "taskId", "status", "contextMode", "fromAgent", "toAgent",
            "childRunDistinct", "mergedKeys", "eventCount", "passed");
    private static final List<String> TASK_IDS = List.of(
            "handoff.fork",
            "handoff.fresh",
            "handoff.target-denied",
            "handoff.cycle-denied",
            "handoff.depth-denied",
            "handoff.state-ownership",
            "handoff.merge-conflict",
            "handoff.timeout");

    @Test
    void evaluatesBoundedHandoffScenariosAndWritesAuditableReport() throws Exception {
        List<EddResult> results = List.of(
                runFork(),
                runFresh(),
                runTargetDenied(),
                runCycleDenied(),
                runDepthDenied(),
                runStateOwnership(),
                runMergeConflict(),
                runTimeout());

        Path report = Path.of("target", "edd", "multi-agent-handoff-edd.json");
        Files.createDirectories(report.getParent());
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of("scenarios", results));

        JsonNode reportJson = mapper.readTree(report.toFile());
        assertThat(results).extracting(EddResult::taskId).containsExactlyElementsOf(TASK_IDS);
        assertThat(reportJson.path("scenarios")).hasSize(TASK_IDS.size());
        for (JsonNode scenario : reportJson.path("scenarios")) {
            Set<String> fields = new LinkedHashSet<>();
            scenario.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrderElementsOf(REPORT_FIELDS);
            assertThat(scenario.path("taskId").asText()).isIn(TASK_IDS);
            assertThat(scenario.path("passed").asBoolean()).isTrue();
            assertThat(scenario.toString())
                    .doesNotContain("执行 worker 子任务")
                    .doesNotContain("D:/workspace")
                    .doesNotContain("java.lang");
        }
        assertThat(results).allSatisfy(result -> assertThat(result.passed()).isTrue());
    }

    private EddResult runFork() {
        AtomicBoolean inherited = new AtomicBoolean();
        GraphRegistry graphs = graphRegistry(state -> {
            inherited.set(state.messages().size() == 2
                    && state.messages().getFirst().equals(ChatMessage.user("父问题")));
            return state.withVariable("worker.result", "done");
        });
        return runSuccess("handoff.fork", HandoffContextMode.FORK, graphs, inherited);
    }

    private EddResult runFresh() {
        AtomicBoolean isolated = new AtomicBoolean();
        GraphRegistry graphs = graphRegistry(state -> {
            isolated.set(state.messages().size() == 1
                    && state.messages().getFirst().equals(ChatMessage.user("执行 worker 子任务")));
            return state.withVariable("worker.result", "done");
        });
        return runSuccess("handoff.fresh", HandoffContextMode.FRESH, graphs, isolated);
    }

    private EddResult runSuccess(
            String taskId,
            HandoffContextMode mode,
            GraphRegistry graphs,
            AtomicBoolean contextVerified) {
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();
        UUID parentRunId = UUID.randomUUID();
        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                catalog(Set.of("worker")), graphs, events::add)) {
            AgentHandoffResult result = executor.execute(
                    parentRunId,
                    parentState(false),
                    handoff(mode, Duration.ofSeconds(1)),
                    HandoffExecutionContext.root("planner", 2, 2)).join();
            boolean passed = contextVerified.get()
                    && !parentRunId.equals(result.childRunId())
                    && "done".equals(result.mergedParentState().variables().get("worker.result"))
                    && events.stream().anyMatch(AgentHandoffEvent.Completed.class::isInstance)
                    && events.stream().noneMatch(AgentHandoffEvent.Failed.class::isInstance);
            return result(taskId, "COMPLETED", mode, true, List.of("worker.result"), events, passed);
        }
    }

    private EddResult runTargetDenied() {
        return runDenied(
                "handoff.target-denied",
                HandoffExecutionContext.root("planner", 2, 2),
                catalog(Set.of()),
                AgentHandoffDeniedException.class);
    }

    private EddResult runCycleDenied() {
        return runDenied(
                "handoff.cycle-denied",
                new HandoffExecutionContext(1, 3, 2, List.of("worker", "planner")),
                catalog(Set.of("worker")),
                AgentHandoffDeniedException.class);
    }

    private EddResult runDepthDenied() {
        return runDenied(
                "handoff.depth-denied",
                new HandoffExecutionContext(1, 1, 2, List.of("root", "planner")),
                catalog(Set.of("worker")),
                AgentHandoffDeniedException.class);
    }

    private EddResult runDenied(
            String taskId,
            HandoffExecutionContext context,
            AgentCatalog catalog,
            Class<? extends RuntimeException> expectedType) {
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();
        boolean passed = false;
        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                catalog, graphRegistry(state -> state.withVariable("worker.result", "done")), events::add)) {
            executor.execute(
                    UUID.randomUUID(),
                    parentState(false),
                    handoff(HandoffContextMode.FRESH, Duration.ofSeconds(1)),
                    context);
        } catch (RuntimeException expected) {
            passed = expectedType.isInstance(expected) && events.isEmpty();
        }
        return result(taskId, "REJECTED", HandoffContextMode.FRESH, null, List.of(), events, passed);
    }

    private EddResult runStateOwnership() {
        GraphRegistry graphs = graphRegistry(state -> state
                .withVariable("worker.result", "done")
                .withVariable("planner.private", "forbidden"));
        return runAsyncFailure(
                "handoff.state-ownership",
                graphs,
                parentState(false),
                Duration.ofSeconds(1),
                AgentHandoffStateException.class,
                "FAILED");
    }

    private EddResult runMergeConflict() {
        GraphRegistry graphs = graphRegistry(state -> state.withVariable("worker.result", "new"));
        return runAsyncFailure(
                "handoff.merge-conflict",
                graphs,
                parentState(true),
                Duration.ofSeconds(1),
                AgentStateMergeException.class,
                "FAILED");
    }

    private EddResult runTimeout() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();
        GraphRegistry graphs = graphRegistry(state -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                interrupted.set(true);
                throw exception;
            }
            return state.withVariable("worker.result", "late");
        });
        EddResult result = runAsyncFailure(
                "handoff.timeout",
                graphs,
                parentState(false),
                Duration.ofMillis(100),
                AgentHandoffTimeoutException.class,
                "TIMED_OUT");
        for (int index = 0; index < 20 && !interrupted.get(); index++) {
            TimeUnit.MILLISECONDS.sleep(25);
        }
        return new EddResult(
                result.taskId(), result.status(), result.contextMode(), result.fromAgent(), result.toAgent(),
                result.childRunDistinct(), result.mergedKeys(), result.eventCount(),
                result.passed() && interrupted.get());
    }

    private EddResult runAsyncFailure(
            String taskId,
            GraphRegistry graphs,
            AgentState parent,
            Duration timeout,
            Class<? extends RuntimeException> rootType,
            String status) {
        List<AgentHandoffEvent> events = new CopyOnWriteArrayList<>();
        UUID parentRunId = UUID.randomUUID();
        boolean passed = false;
        try (AgentHandoffExecutor executor = new AgentHandoffExecutor(
                catalog(Set.of("worker")), graphs, events::add)) {
            executor.execute(
                    parentRunId,
                    parent,
                    handoff(HandoffContextMode.FRESH, timeout),
                    HandoffExecutionContext.root("planner", 2, 2)).join();
        } catch (RuntimeException expected) {
            passed = rootType.isInstance(rootCause(expected))
                    && events.stream().anyMatch(AgentHandoffEvent.Failed.class::isInstance)
                    && events.stream().noneMatch(AgentHandoffEvent.Completed.class::isInstance);
        }
        Boolean childRunDistinct = events.isEmpty()
                ? null
                : events.stream().allMatch(event -> !parentRunId.equals(event.childRunId()));
        return result(
                taskId,
                status,
                HandoffContextMode.FRESH,
                childRunDistinct,
                List.of(),
                events,
                passed && Boolean.TRUE.equals(childRunDistinct));
    }

    private Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
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

    private GraphRegistry graphRegistry(com.agent.core.engine.Node worker) {
        return new GraphRegistry(Map.of("worker-graph", () -> {
            StateGraph graph = new StateGraph(3);
            graph.addNode("worker", worker);
            graph.setEntryPoint("worker");
            graph.addEdge("worker", StateGraph.END);
            return graph;
        }));
    }

    private AgentState parentState(boolean conflictingOutput) {
        AgentState state = new AgentState(
                List.of(ChatMessage.user("父问题")),
                Map.of("workspacePath", "D:/workspace"),
                List.of());
        return conflictingOutput ? state.withVariable("worker.result", "old") : state;
    }

    private AgentHandoff handoff(HandoffContextMode mode, Duration timeout) {
        return new AgentHandoff(
                UUID.randomUUID(),
                "planner",
                "worker",
                "执行 worker 子任务",
                mode,
                Set.of("worker.result"),
                timeout);
    }

    private EddResult result(
            String taskId,
            String status,
            HandoffContextMode mode,
            Boolean childRunDistinct,
            List<String> mergedKeys,
            List<AgentHandoffEvent> events,
            boolean passed) {
        return new EddResult(
                taskId,
                status,
                mode.name(),
                "planner",
                "worker",
                childRunDistinct,
                List.copyOf(mergedKeys),
                events.size(),
                passed);
    }

    private record EddResult(
            String taskId,
            String status,
            String contextMode,
            String fromAgent,
            String toAgent,
            Boolean childRunDistinct,
            List<String> mergedKeys,
            int eventCount,
            boolean passed) {
    }
}
