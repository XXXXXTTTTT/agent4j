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

    private static final class TrackingBrowserAutomation implements BrowserAutomation {

        private final RuntimeException closeFailure;
        private boolean closed;

        private TrackingBrowserAutomation() {
            this(null);
        }

        private TrackingBrowserAutomation(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
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
        public CompletableFuture<BrowserScreenshot> screenshot(Duration timeout) {
            return CompletableFuture.completedFuture(
                    new BrowserScreenshot(new byte[] {1}, "image/png"));
        }

        @Override
        public void close() {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
