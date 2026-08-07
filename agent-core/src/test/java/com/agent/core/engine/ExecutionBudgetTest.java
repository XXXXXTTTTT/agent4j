package com.agent.core.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionBudgetTest {

    @Test
    void rejectsNonPositiveBudgetValues() {
        assertThatThrownBy(() -> new ExecutionBudget(
                Duration.ZERO, Duration.ofSeconds(1), 10, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDuration");
        assertThatThrownBy(() -> new ExecutionBudget(
                Duration.ofSeconds(1), Duration.ZERO, 10, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idleTimeout");
        assertThatThrownBy(() -> new ExecutionBudget(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenBudget");
        assertThatThrownBy(() -> new ExecutionBudget(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 10, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSteps");
        assertThatThrownBy(() -> new ExecutionBudget(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 10, 3, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noProgressLimit");
    }

    @Test
    void exposesAllStopReasonFields() {
        ExecutionBudgetExceededException exception = new ExecutionBudgetExceededException(
                ExecutionStopReason.TOKEN_BUDGET, 101, 100);

        assertThat(exception.reason()).isEqualTo(ExecutionStopReason.TOKEN_BUDGET);
        assertThat(exception.observed()).isEqualTo(101);
        assertThat(exception.limit()).isEqualTo(100);
        assertThat(exception).hasMessageContaining("TOKEN_BUDGET");
    }

    @Test
    void accumulatesTokensAndRefreshesProgressClockInsideContext() throws Exception {
        AtomicLong observedTotal = new AtomicLong();
        AtomicInteger progressTicks = new AtomicInteger();
        AtomicReference<String> summary = new AtomicReference<>();
        NodeExecutionContext context = new NodeExecutionContext(UUID.randomUUID(), "planner");

        long result = NodeExecutionContext.callWithin(
                context,
                summary::set,
                observedTotal::set,
                progressTicks::incrementAndGet,
                () -> {
                    NodeExecutionContext.consumeTokens(4);
                    NodeExecutionContext.progress("已完成提示");
                    NodeExecutionContext.consumeTokens(3);
                    return NodeExecutionContext.consumedTokens();
                });

        assertThat(result).isEqualTo(7);
        assertThat(observedTotal).hasValue(7);
        assertThat(progressTicks).hasValue(1);
        assertThat(summary).hasValue("已完成提示");
        assertThat(NodeExecutionContext.consumedTokens()).isZero();
    }

    @Test
    void rejectsNegativeTokenConsumption() throws Exception {
        NodeExecutionContext context = new NodeExecutionContext(UUID.randomUUID(), "planner");

        assertThatThrownBy(() -> NodeExecutionContext.callWithin(
                context,
                ignored -> { },
                ignored -> { },
                () -> { },
                () -> {
                    NodeExecutionContext.consumeTokens(-1);
                    return null;
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokens");
    }
}
