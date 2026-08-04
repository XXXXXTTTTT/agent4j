package com.agent.web.trace;

import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;
import com.agent.web.log.InMemoryRunLogEventBus;
import com.agent.web.log.RunLogSubscription;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLifecycleEventPublisherTest {

    private static final UUID RUN_ID = UUID.fromString(
            "3b3d1592-c439-49da-956b-dd9ec8de5344");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-02T01:00:00Z");

    @Test
    void delegatesTraceBeforeCompletingLogsForEveryTerminalType() {
        for (TraceEvent terminal : List.of(
                new TraceEvent.Completed(UUID.randomUUID(), RUN_ID, 1, OCCURRED_AT),
                new TraceEvent.Failed(UUID.randomUUID(), RUN_ID, 1, OCCURRED_AT, "failure"),
                new TraceEvent.Rejected(
                        UUID.randomUUID(), RUN_ID, 1, OCCURRED_AT, "ops", "rejected"))) {
            try (InMemoryRunLogEventBus logBus = new InMemoryRunLogEventBus()) {
                List<TraceEvent> traces = new ArrayList<>();
                RunLogSubscription subscription = logBus.openSubscription(RUN_ID);
                RunLifecycleEventPublisher publisher = new RunLifecycleEventPublisher(
                        traces::add, logBus);

                publisher.publish(terminal);

                assertThat(traces).containsExactly(terminal);
                StepVerifier.create(subscription.events()).verifyComplete();
            }
        }
    }

    @Test
    void completesLogsWhenTerminalTraceDelegateThrows() {
        try (InMemoryRunLogEventBus logBus = new InMemoryRunLogEventBus()) {
            RunLogSubscription subscription = logBus.openSubscription(RUN_ID);
            TraceEventPublisher failure = event -> {
                throw new IllegalStateException("trace unavailable");
            };
            RunLifecycleEventPublisher publisher = new RunLifecycleEventPublisher(
                    failure, logBus);
            TraceEvent terminal = new TraceEvent.Completed(
                    UUID.randomUUID(), RUN_ID, 1, OCCURRED_AT);

            assertThatThrownBy(() -> publisher.publish(terminal))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("trace unavailable");
            StepVerifier.create(subscription.events()).verifyComplete();
        }
    }

    @Test
    void publishesToEveryDelegateAndAggregatesFailuresInOrder() {
        try (InMemoryRunLogEventBus logBus = new InMemoryRunLogEventBus()) {
            List<TraceEvent> delivered = new ArrayList<>();
            TraceEventPublisher first = event -> {
                throw new IllegalStateException("first failure");
            };
            TraceEventPublisher second = event -> {
                throw new IllegalArgumentException("second failure");
            };
            TraceEventPublisher third = delivered::add;
            RunLifecycleEventPublisher publisher = new RunLifecycleEventPublisher(
                    List.of(first, second, third), logBus);
            TraceEvent event = new TraceEvent.NodeStarted(
                    UUID.randomUUID(), RUN_ID, 1, OCCURRED_AT, "coder");

            assertThatThrownBy(() -> publisher.publish(event))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("first failure")
                    .satisfies(failure -> assertThat(failure.getSuppressed())
                            .singleElement()
                            .isInstanceOfSatisfying(IllegalArgumentException.class, suppressed ->
                                    assertThat(suppressed).hasMessage("second failure")));
            assertThat(delivered).containsExactly(event);
        }
    }

    @Test
    void completesLogsWhenEveryTerminalDelegateFails() {
        try (InMemoryRunLogEventBus logBus = new InMemoryRunLogEventBus()) {
            RunLogSubscription subscription = logBus.openSubscription(RUN_ID);
            RunLifecycleEventPublisher publisher = new RunLifecycleEventPublisher(
                    List.of(
                            event -> { throw new IllegalStateException("first"); },
                            event -> { throw new IllegalArgumentException("second"); }),
                    logBus);
            TraceEvent terminal = new TraceEvent.Completed(
                    UUID.randomUUID(), RUN_ID, 1, OCCURRED_AT);

            assertThatThrownBy(() -> publisher.publish(terminal))
                    .isInstanceOf(IllegalStateException.class);
            StepVerifier.create(subscription.events()).verifyComplete();
        }
    }
}
