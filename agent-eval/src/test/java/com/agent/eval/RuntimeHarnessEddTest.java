package com.agent.eval;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ExecutionBudget;
import com.agent.core.engine.ExecutionBudgetExceededException;
import com.agent.core.engine.ExecutionStopReason;
import com.agent.core.engine.InterruptPolicy;
import com.agent.core.engine.NodeExecutionContext;
import com.agent.core.engine.StateGraph;
import com.agent.core.harness.HarnessEvent;
import com.agent.core.harness.HarnessEventType;
import com.agent.core.harness.HarnessHookChain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 对预算停止和 Harness 证据执行确定性工程 EDD。 */
class RuntimeHarnessEddTest {

    @Test
    void evaluatesRuntimeStopsAndWritesAuditReport() throws Exception {
        List<RuntimeEddResult> results = List.of(
                tokenBudgetScenario(),
                noProgressScenario());
        Path reportDirectory = Path.of("target", "edd");
        Files.createDirectories(reportDirectory);
        Path report = reportDirectory.resolve("runtime-harness-edd.json");
        new ObjectMapper().findAndRegisterModules()
                .writerWithDefaultPrettyPrinter()
                .writeValue(report.toFile(), new RuntimeEddReport(Instant.now(), results));

        assertThat(results).allSatisfy(result -> assertThat(result.passed())
                .as(result.taskId() + " EDD 失败")
                .isTrue());
        assertThat(report).isRegularFile();
    }

    private RuntimeEddResult tokenBudgetScenario() {
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        ExecutionBudget budget = new ExecutionBudget(
                Duration.ofSeconds(2), Duration.ofSeconds(1), 5, 3, 2);
        try (StateGraph graph = new StateGraph(
                budget,
                InterruptPolicy.never(),
                new HarnessHookChain(List.of(events::add)))) {
            graph.addNode("model", state -> {
                        NodeExecutionContext.consumeTokens(6);
                        return state;
                    })
                    .addEdge("model", StateGraph.END)
                    .setEntryPoint("model");
            graph.execute(AgentState.empty());
            throw new AssertionError("TOKEN_BUDGET 场景必须停止");
        } catch (ExecutionBudgetExceededException exception) {
            return result(
                    "runtime.token-budget",
                    ExecutionStopReason.TOKEN_BUDGET,
                    exception,
                    events);
        }
    }

    private RuntimeEddResult noProgressScenario() {
        List<HarnessEvent> events = new CopyOnWriteArrayList<>();
        ExecutionBudget budget = new ExecutionBudget(
                Duration.ofSeconds(2), Duration.ofSeconds(1), 100, 4, 1);
        try (StateGraph graph = new StateGraph(
                budget,
                InterruptPolicy.never(),
                new HarnessHookChain(List.of(events::add)))) {
            graph.addNode("loop", state -> state.withTraceEntry("loop"))
                    .addEdge("loop", "loop")
                    .setEntryPoint("loop");
            graph.execute(AgentState.empty());
            throw new AssertionError("NO_PROGRESS 场景必须停止");
        } catch (ExecutionBudgetExceededException exception) {
            return result(
                    "runtime.no-progress",
                    ExecutionStopReason.NO_PROGRESS,
                    exception,
                    events);
        }
    }

    private RuntimeEddResult result(
            String taskId,
            ExecutionStopReason expectedReason,
            ExecutionBudgetExceededException exception,
            List<HarnessEvent> events) {
        HarnessEvent terminalEvent = events.getLast();
        boolean passed = exception.reason() == expectedReason
                && terminalEvent.eventType() == HarnessEventType.BUDGET_EXHAUSTED
                && terminalEvent.metadata().get("reason").equals(expectedReason.name());
        return new RuntimeEddResult(
                taskId,
                passed,
                exception.reason().name(),
                exception.observed(),
                exception.limit(),
                terminalEvent.state().trace(),
                exception.getClass().getName(),
                events.stream().map(event -> event.eventType().name()).toList());
    }

    private record RuntimeEddReport(
            Instant generatedAt,
            List<RuntimeEddResult> scenarios) {
    }

    private record RuntimeEddResult(
            String taskId,
            boolean passed,
            String stopReason,
            long observed,
            long limit,
            List<String> trace,
            String errorClassification,
            List<String> harnessEvents) {
    }
}
