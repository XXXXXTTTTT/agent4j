package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateGraphBudgetTest {

    @Test
    void interruptsBlockedNodeAtMaximumDuration() {
        AtomicBoolean interrupted = new AtomicBoolean();
        ExecutionBudget budget = budget(Duration.ofMillis(40), Duration.ofSeconds(1), 100, 5, 5);

        try (StateGraph graph = new StateGraph(budget, InterruptPolicy.never())) {
            graph.addNode("slow", state -> sleepUntilInterrupted(interrupted, state))
                    .addEdge("slow", StateGraph.END)
                    .setEntryPoint("slow");

            assertStopReason(graph, ExecutionStopReason.MAX_DURATION, 1);
        }
        assertThat(awaitTrue(interrupted, Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void interruptsBlockedNodeAtIdleTimeout() {
        AtomicBoolean interrupted = new AtomicBoolean();
        ExecutionBudget budget = budget(Duration.ofSeconds(1), Duration.ofMillis(40), 100, 5, 5);

        try (StateGraph graph = new StateGraph(budget, InterruptPolicy.never())) {
            graph.addNode("idle", state -> sleepUntilInterrupted(interrupted, state))
                    .addEdge("idle", StateGraph.END)
                    .setEntryPoint("idle");

            assertStopReason(graph, ExecutionStopReason.IDLE_TIMEOUT, 1);
        }
        assertThat(awaitTrue(interrupted, Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void stopsImmediatelyWhenTokenBudgetIsExceeded() {
        ExecutionBudget budget = budget(Duration.ofSeconds(1), Duration.ofSeconds(1), 5, 5, 5);

        try (StateGraph graph = new StateGraph(budget, InterruptPolicy.never())) {
            graph.addNode("model", state -> {
                        NodeExecutionContext.consumeTokens(6);
                        return state;
                    })
                    .addEdge("model", StateGraph.END)
                    .setEntryPoint("model");

            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .isInstanceOfSatisfying(ExecutionBudgetExceededException.class, exception -> {
                        assertThat(exception.reason()).isEqualTo(ExecutionStopReason.TOKEN_BUDGET);
                        assertThat(exception.observed()).isEqualTo(6);
                        assertThat(exception.limit()).isEqualTo(5);
                        assertThat(exception.consumedTokens()).isEqualTo(6);
                    });
        }
    }

    @Test
    void preservesLegacyMaximumStepsException() {
        AtomicInteger changes = new AtomicInteger();
        ExecutionBudget budget = budget(Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 1, 5);

        try (StateGraph graph = new StateGraph(budget, InterruptPolicy.never())) {
            graph.addNode("loop", state -> state.withVariable(
                            "value", Integer.toString(changes.incrementAndGet())))
                    .addEdge("loop", "loop")
                    .setEntryPoint("loop");

            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .isInstanceOfSatisfying(MaxStepsExceededException.class, exception -> {
                        assertThat(exception.reason()).isEqualTo(ExecutionStopReason.MAX_STEPS);
                        assertThat(exception.maxSteps()).isEqualTo(1);
                    });
        }
    }

    @Test
    void ignoresTraceGrowthWhenDetectingNoProgress() {
        ExecutionBudget budget = budget(Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 10, 2);

        try (StateGraph graph = new StateGraph(budget, InterruptPolicy.never())) {
            graph.addNode("loop", state -> state.withTraceEntry("loop"))
                    .addEdge("loop", "loop")
                    .setEntryPoint("loop");

            assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                    .isInstanceOfSatisfying(ExecutionBudgetExceededException.class, exception -> {
                        assertThat(exception.reason()).isEqualTo(ExecutionStopReason.NO_PROGRESS);
                        assertThat(exception.observed()).isEqualTo(2);
                        assertThat(exception.limit()).isEqualTo(2);
                    });
        }
    }

    private AgentState sleepUntilInterrupted(AtomicBoolean interrupted, AgentState state)
            throws InterruptedException {
        try {
            Thread.sleep(Duration.ofSeconds(5));
            return state;
        } catch (InterruptedException exception) {
            interrupted.set(true);
            throw exception;
        }
    }

    private ExecutionBudget budget(
            Duration maxDuration,
            Duration idleTimeout,
            long tokenBudget,
            int maxSteps,
            int noProgressLimit) {
        return new ExecutionBudget(
                maxDuration, idleTimeout, tokenBudget, maxSteps, noProgressLimit);
    }

    private boolean awaitTrue(AtomicBoolean value, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!value.get() && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
        return value.get();
    }

    private void assertStopReason(
            StateGraph graph,
            ExecutionStopReason reason,
            long minimumObserved) {
        assertThatThrownBy(() -> graph.execute(AgentState.empty()))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(reason);
                    assertThat(exception.observed()).isGreaterThanOrEqualTo(minimumObserved);
                    assertThat(exception.limit()).isPositive();
                });
    }
}
