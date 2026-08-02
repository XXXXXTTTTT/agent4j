package com.agent.web.log;

import com.agent.core.trace.RunLogEvent;
import com.agent.core.trace.RunLogStream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRunLogEventBusTest {

    private static final UUID RUN_ID = UUID.fromString(
            "79b90e34-9fcb-43dc-9a19-31b1a6c9b155");

    @Test
    void publishesInOrderToIndependentSubscribersWithoutReplay() {
        try (InMemoryRunLogEventBus bus = new InMemoryRunLogEventBus()) {
            bus.publish(event(0));
            RunLogSubscription first = bus.openSubscription(RUN_ID);
            RunLogSubscription second = bus.openSubscription(RUN_ID);
            RunLogEvent one = event(1);
            RunLogEvent two = event(2);

            bus.publish(one);
            bus.publish(two);
            bus.complete(RUN_ID);

            StepVerifier.create(first.events())
                    .expectNext(one, two)
                    .verifyComplete();
            StepVerifier.create(second.events())
                    .expectNext(one, two)
                    .verifyComplete();
            StepVerifier.create(bus.subscribe(RUN_ID))
                    .then(() -> bus.complete(RUN_ID))
                    .verifyComplete();
        }
    }

    @Test
    void disconnectsOnlySubscriberWhoseBufferOverflows() throws Exception {
        try (InMemoryRunLogEventBus bus = new InMemoryRunLogEventBus()) {
            RunLogSubscription slow = bus.openSubscription(RUN_ID);
            RunLogSubscription healthy = bus.openSubscription(RUN_ID);
            CountDownLatch slowCompleted = new CountDownLatch(1);
            List<RunLogEvent> healthyEvents = new CopyOnWriteArrayList<>();
            slow.events().subscribe(new BaseSubscriber<>() {
                @Override
                protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
                    request(1);
                }

                @Override
                protected void hookOnComplete() {
                    slowCompleted.countDown();
                }
            });
            healthy.events().subscribe(healthyEvents::add);

            for (int index = 0; index < 1026; index++) {
                bus.publish(event(index));
            }

            assertThat(slowCompleted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            bus.publish(event(1026));
            assertThat(healthyEvents).hasSize(1027);
        }
    }

    @Test
    void closeCompletesCurrentSubscribersAndRejectsNewOperations() {
        InMemoryRunLogEventBus bus = new InMemoryRunLogEventBus();
        RunLogSubscription subscription = bus.openSubscription(RUN_ID);

        bus.close();

        StepVerifier.create(subscription.events()).verifyComplete();
        assertThatThrownBy(() -> bus.publish(event(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bus.subscribe(RUN_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bus.openSubscription(RUN_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RunLogEvent event(long sequence) {
        return new RunLogEvent(
                UUID.randomUUID(),
                RUN_ID,
                "ops",
                sequence,
                RunLogStream.PTY,
                "line-" + sequence,
                Instant.parse("2026-08-02T01:00:00Z").plusMillis(sequence));
    }
}
