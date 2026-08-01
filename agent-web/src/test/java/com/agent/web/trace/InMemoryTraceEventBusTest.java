package com.agent.web.trace;

import com.agent.core.trace.TraceEvent;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTraceEventBusTest {

    private static final UUID RUN_A =
            UUID.fromString("61fb728f-f62f-45d1-831f-643ea27ec2b4");
    private static final UUID RUN_B =
            UUID.fromString("0bbc8de3-a230-4341-88ce-7dfcf346e1ec");
    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");

    @Test
    void isolatesRunsAndPreservesPublicationOrder() {
        try (InMemoryTraceEventBus bus = new InMemoryTraceEventBus()) {
            List<TraceEvent> eventsA = new ArrayList<>();
            List<TraceEvent> eventsB = new ArrayList<>();
            Disposable subscriptionA = bus.subscribe(RUN_A).subscribe(eventsA::add);
            Disposable subscriptionB = bus.subscribe(RUN_B).subscribe(eventsB::add);
            TraceEvent firstA = started(RUN_A, 1, "coder");
            TraceEvent firstB = started(RUN_B, 1, "planner");
            TraceEvent secondA = started(RUN_A, 2, "ops");

            bus.publish(firstA);
            bus.publish(firstB);
            bus.publish(secondA);

            assertThat(eventsA).containsExactly(firstA, secondA);
            assertThat(eventsB).containsExactly(firstB);
            subscriptionA.dispose();
            subscriptionB.dispose();
        }
    }

    @Test
    void doesNotReplayEventsPublishedWithoutAnActiveSubscriber() {
        try (InMemoryTraceEventBus bus = new InMemoryTraceEventBus()) {
            TraceEvent oldEvent = started(RUN_A, 0, "old");
            TraceEvent liveEvent = started(RUN_A, 1, "live");
            bus.publish(oldEvent);
            List<TraceEvent> events = new ArrayList<>();

            Disposable subscription = bus.subscribe(RUN_A).subscribe(events::add);
            bus.publish(liveEvent);

            assertThat(events).containsExactly(liveEvent);
            subscription.dispose();
        }
    }

    @Test
    void removesCancelledSubscriptionAndAllowsANewSubscriber() {
        try (InMemoryTraceEventBus bus = new InMemoryTraceEventBus()) {
            List<TraceEvent> firstEvents = new ArrayList<>();
            Disposable first = bus.subscribe(RUN_A).subscribe(firstEvents::add);
            first.dispose();
            List<TraceEvent> secondEvents = new ArrayList<>();
            Disposable second = bus.subscribe(RUN_A).subscribe(secondEvents::add);
            TraceEvent event = started(RUN_A, 1, "reviewer");

            bus.publish(event);

            assertThat(firstEvents).isEmpty();
            assertThat(secondEvents).containsExactly(event);
            second.dispose();
        }
    }

    @Test
    void terminalEventCompletesCurrentStreamWithoutReplay() {
        try (InMemoryTraceEventBus bus = new InMemoryTraceEventBus()) {
            List<TraceEvent> events = new ArrayList<>();
            AtomicBoolean completed = new AtomicBoolean();
            TraceEvent terminal = new TraceEvent.Completed(
                    UUID.fromString("aa94949e-e83f-40c7-931a-4b08708590b8"),
                    RUN_A,
                    3,
                    NOW);
            bus.subscribe(RUN_A).subscribe(events::add, ignored -> { }, () -> completed.set(true));

            bus.publish(terminal);

            assertThat(events).containsExactly(terminal);
            assertThat(completed).isTrue();
            List<TraceEvent> laterEvents = new ArrayList<>();
            Disposable later = bus.subscribe(RUN_A).subscribe(laterEvents::add);
            assertThat(laterEvents).isEmpty();
            later.dispose();
        }
    }

    @Test
    void completesAndRemovesSubscriptionWhenBoundedBufferOverflows() {
        try (InMemoryTraceEventBus bus = new InMemoryTraceEventBus()) {
            ControlledSubscriber subscriber = new ControlledSubscriber();
            bus.subscribe(RUN_A).subscribe(subscriber);

            for (int index = 0; index < 257; index++) {
                bus.publish(started(RUN_A, index, "node-" + index));
            }
            subscriber.requestAll();

            assertThat(subscriber.events).hasSize(256);
            assertThat(subscriber.completed).isTrue();

            List<TraceEvent> replacementEvents = new ArrayList<>();
            Disposable replacement = bus.subscribe(RUN_A).subscribe(replacementEvents::add);
            TraceEvent replacementEvent = started(RUN_A, 258, "replacement");
            bus.publish(replacementEvent);
            assertThat(replacementEvents).containsExactly(replacementEvent);
            replacement.dispose();
        }
    }

    @Test
    void closeCompletesStreamsAndRejectsFurtherUse() {
        InMemoryTraceEventBus bus = new InMemoryTraceEventBus();
        AtomicBoolean completed = new AtomicBoolean();
        bus.subscribe(RUN_A).subscribe(ignored -> { }, ignored -> { }, () -> completed.set(true));

        bus.close();

        assertThat(completed).isTrue();
        assertThatThrownBy(() -> bus.publish(started(RUN_A, 1, "coder")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TraceEventBus 已关闭");
        assertThatThrownBy(() -> bus.subscribe(RUN_A))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TraceEventBus 已关闭");
    }

    private static TraceEvent started(UUID runId, long version, String nodeName) {
        return new TraceEvent.NodeStarted(UUID.randomUUID(), runId, version, NOW, nodeName);
    }

    private static final class ControlledSubscriber extends BaseSubscriber<TraceEvent> {

        private final List<TraceEvent> events = new ArrayList<>();
        private boolean completed;

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // 测试主动控制请求量，以稳定填满 256 项缓冲区。
        }

        @Override
        protected void hookOnNext(TraceEvent value) {
            events.add(value);
        }

        @Override
        protected void hookOnComplete() {
            completed = true;
        }

        private void requestAll() {
            request(Long.MAX_VALUE);
        }
    }
}
