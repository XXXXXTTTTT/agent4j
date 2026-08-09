package com.agent.core.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证端点并发和速率预算。 */
class InferenceAdmissionControllerTest {

    @Test
    void rejectsWhenConcurrentPermitCannotBeAcquiredBeforeQueueTimeout() throws Exception {
        InferenceAdmissionController controller = new InferenceAdmissionController(
                new InferenceBudget(1, 10, Duration.ofMillis(20)),
                Clock.systemUTC());

        InferencePermit first = controller.acquire();
        try {
            assertThatThrownBy(controller::acquire)
                    .isInstanceOfSatisfying(
                            InferenceAdmissionException.class,
                            exception -> assertThat(exception.reason())
                                    .isEqualTo(InferenceRejectionReason.CONCURRENCY_LIMIT));
            assertThat(controller.snapshot().activeRequests()).isEqualTo(1);
        } finally {
            first.close();
        }
        assertThat(controller.snapshot().activeRequests()).isZero();
    }

    @Test
    void enforcesRateWindowAndRecoversAfterOneMinute() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T12:00:00Z"));
        InferenceAdmissionController controller = new InferenceAdmissionController(
                new InferenceBudget(2, 2, Duration.ZERO), clock);

        controller.acquire().close();
        controller.acquire().close();
        assertThatThrownBy(controller::acquire)
                .isInstanceOfSatisfying(
                        InferenceAdmissionException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(InferenceRejectionReason.RATE_LIMIT));

        clock.advance(Duration.ofMinutes(1));
        InferencePermit recovered = controller.acquire();
        recovered.close();
        assertThat(controller.snapshot().requestsInWindow()).isEqualTo(1);
        assertThat(controller.snapshot().rateLimitRejections()).isEqualTo(1);
    }

    @Test
    void closingPermitTwiceReleasesOnlyOnceAndRejectsInvalidBudget() {
        InferenceAdmissionController controller = new InferenceAdmissionController(
                new InferenceBudget(1, 10, Duration.ZERO), Clock.systemUTC());
        InferencePermit permit = controller.acquire();
        permit.close();
        permit.close();

        InferencePermit second = controller.acquire();
        second.close();
        assertThatThrownBy(() -> new InferenceBudget(0, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrentRequests");
        assertThatThrownBy(() -> new InferenceBudget(1, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRequestsPerMinute");
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
