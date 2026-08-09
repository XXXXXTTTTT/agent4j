package com.agent.core.gui;

import com.agent.sandbox.browser.BrowserAutomation;
import com.agent.sandbox.browser.BrowserScreenshot;
import com.agent.sandbox.browser.NavigationResult;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserSessionRegistryTest {

    private static final UUID FIRST_RUN = UUID.fromString(
            "d65e75c1-74b3-4968-b542-887d96b6d6d5");
    private static final UUID SECOND_RUN = UUID.fromString(
            "946ba856-3563-4763-bf11-c18868bfedaf");

    @Test
    void ownsOneExactSessionPerRunAndRemovesItOnClose() {
        TrackingBrowserAutomation browser = new TrackingBrowserAutomation();
        AtomicInteger creations = new AtomicInteger();
        BrowserSessionRegistry registry = new BrowserSessionRegistry(() -> {
            creations.incrementAndGet();
            return browser;
        });

        assertThat(registry.open(FIRST_RUN)).isSameAs(browser);
        assertThat(registry.require(FIRST_RUN)).isSameAs(browser);
        assertThatThrownBy(() -> registry.open(FIRST_RUN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FIRST_RUN.toString());
        assertThat(creations).hasValue(1);

        registry.close(FIRST_RUN);

        assertThat(browser.closed).isTrue();
        assertThatThrownBy(() -> registry.require(FIRST_RUN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FIRST_RUN.toString());
        registry.close();
    }

    @Test
    void supplierFailureDoesNotRegisterRun() {
        IllegalArgumentException failure = new IllegalArgumentException("create failed");
        BrowserSessionRegistry registry = new BrowserSessionRegistry(() -> {
            throw failure;
        });

        assertThatThrownBy(() -> registry.open(FIRST_RUN)).isSameAs(failure);
        assertThatThrownBy(() -> registry.require(FIRST_RUN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FIRST_RUN.toString());
        registry.close();
    }

    @Test
    void closeAllSessionsAndPreservesLaterCleanupFailuresAsSuppressed() {
        RuntimeException firstFailure = new IllegalStateException("first close failed");
        RuntimeException secondFailure = new IllegalArgumentException("second close failed");
        TrackingBrowserAutomation first = new TrackingBrowserAutomation(firstFailure);
        TrackingBrowserAutomation second = new TrackingBrowserAutomation(secondFailure);
        Queue<TrackingBrowserAutomation> browsers = new ArrayDeque<>();
        browsers.add(first);
        browsers.add(second);
        BrowserSessionRegistry registry = new BrowserSessionRegistry(browsers::remove);
        registry.open(FIRST_RUN);
        registry.open(SECOND_RUN);

        assertThatThrownBy(registry::close)
                .isInstanceOf(RuntimeException.class)
                .satisfies(exception -> {
                    assertThat(exception).isIn(firstFailure, secondFailure);
                    RuntimeException other = exception == firstFailure
                            ? secondFailure : firstFailure;
                    assertThat(exception.getSuppressed()).containsExactly(other);
                });
        assertThat(first.closed).isTrue();
        assertThat(second.closed).isTrue();
    }

    @Test
    void retainsFailedSessionForASecondCleanupAttempt() {
        TrackingBrowserAutomation browser = new TrackingBrowserAutomation(
                new IllegalStateException("first close failed"));
        BrowserSessionRegistry registry = new BrowserSessionRegistry(() -> browser);
        registry.open(FIRST_RUN);

        assertThatThrownBy(() -> registry.close(FIRST_RUN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("first close failed");
        browser.closeFailure = null;

        registry.close(FIRST_RUN);

        assertThat(browser.closed).isTrue();
        assertThatThrownBy(() -> registry.require(FIRST_RUN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FIRST_RUN.toString());
        registry.close();
    }

    @Test
    void rejectsFactoryReturningAnActiveSessionForAnotherRun() {
        TrackingBrowserAutomation browser = new TrackingBrowserAutomation();
        BrowserSessionRegistry registry = new BrowserSessionRegistry(() -> browser);
        registry.open(FIRST_RUN);

        assertThatThrownBy(() -> registry.open(SECOND_RUN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("独占");
        assertThat(registry.require(FIRST_RUN)).isSameAs(browser);
        registry.close();
    }

    @Test
    void serializesCloseWithAnOpenThatIsCreatingItsSession() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        TrackingBrowserAutomation browser = new TrackingBrowserAutomation();
        BrowserSessionRegistry registry = new BrowserSessionRegistry(() -> {
            factoryEntered.countDown();
            await(releaseFactory);
            return browser;
        });

        CompletableFuture<Void> opening = CompletableFuture.runAsync(() -> registry.open(FIRST_RUN));
        assertThat(factoryEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> closing = CompletableFuture.runAsync(registry::close);
        releaseFactory.countDown();

        opening.get(5, TimeUnit.SECONDS);
        closing.get(5, TimeUnit.SECONDS);

        assertThat(browser.closed).isTrue();
        registry.close();
    }

    @Test
    void doesNotReturnSessionWhileItsCloseIsInProgress() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch requireStarted = new CountDownLatch(1);
        TrackingBrowserAutomation browser = new TrackingBrowserAutomation(
                null, closeEntered, releaseClose);
        BrowserSessionRegistry registry = new BrowserSessionRegistry(() -> browser);
        registry.open(FIRST_RUN);

        CompletableFuture<Void> closing = CompletableFuture.runAsync(
                () -> registry.close(FIRST_RUN));
        assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<BrowserAutomation> requiring = CompletableFuture.supplyAsync(() -> {
            requireStarted.countDown();
            return registry.require(FIRST_RUN);
        });
        assertThat(requireStarted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> requiring.get(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        releaseClose.countDown();
        closing.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> requiring.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
        registry.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试工厂被中断", exception);
        }
    }

    private static final class TrackingBrowserAutomation implements BrowserAutomation {

        private RuntimeException closeFailure;
        private boolean closed;
        private final CountDownLatch closeEntered;
        private final CountDownLatch releaseClose;

        private TrackingBrowserAutomation() {
            this(null);
        }

        private TrackingBrowserAutomation(RuntimeException closeFailure) {
            this(closeFailure, null, null);
        }

        private TrackingBrowserAutomation(
                RuntimeException closeFailure,
                CountDownLatch closeEntered,
                CountDownLatch releaseClose) {
            this.closeFailure = closeFailure;
            this.closeEntered = closeEntered;
            this.releaseClose = releaseClose;
        }

        @Override
        public CompletableFuture<NavigationResult> navigate(URI url, Duration timeout) {
            return CompletableFuture.completedFuture(
                    new NavigationResult(url, url, OptionalInt.empty()));
        }

        @Override
        public CompletableFuture<Void> click(String selector, Duration timeout) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<String> extractDom() {
            return CompletableFuture.completedFuture("<html></html>");
        }

        @Override
        public CompletableFuture<String> extractDom(Duration timeout) {
            return CompletableFuture.completedFuture("<html></html>");
        }

        @Override
        public CompletableFuture<BrowserScreenshot> screenshot(Duration timeout) {
            return CompletableFuture.completedFuture(
                    new BrowserScreenshot(new byte[] {1}, "image/png"));
        }

        @Override
        public void close() {
            closed = true;
            if (closeEntered != null) {
                closeEntered.countDown();
                await(releaseClose);
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
