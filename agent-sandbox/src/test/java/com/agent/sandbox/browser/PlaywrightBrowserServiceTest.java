package com.agent.sandbox.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlaywrightBrowserServiceTest {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private HttpServer httpServer;
    private URI pageUri;
    private PlaywrightBrowserService service;

    @BeforeAll
    static void requireLaunchableChromium() {
        Playwright playwright = null;
        Browser browser = null;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
        } catch (PlaywrightException exception) {
            assumeTrue(false, "当前环境无法启动 Playwright Chromium: " + exception.getMessage());
        } finally {
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
        }
    }

    @BeforeEach
    void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/page", this::servePage);
        httpServer.start();
        pageUri = URI.create("http://127.0.0.1:"
                + httpServer.getAddress().getPort() + "/page");
        service = new PlaywrightBrowserService();
    }

    @AfterEach
    void stopResources() {
        if (service != null) {
            service.close();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void navigatesClicksExtractsDomAndCapturesFullPagePng() {
        NavigationResult navigation = service.navigate(pageUri, Duration.ofSeconds(15)).join();

        assertThat(navigation.requestedUrl()).isEqualTo(pageUri);
        assertThat(navigation.finalUrl()).isEqualTo(pageUri);
        assertThat(navigation.statusCode()).hasValue(200);
        assertThat(service.extractDom(Duration.ofSeconds(15)).join()).contains("before");

        service.click("#change", Duration.ofSeconds(15)).join();

        assertThat(service.extractDom(Duration.ofSeconds(15)).join()).contains("after");
        BrowserScreenshot screenshot = service.screenshot(Duration.ofSeconds(15)).join();
        assertThat(screenshot.pngBytes()).startsWith(PNG_SIGNATURE);
        assertThat(readPngHeight(screenshot.pngBytes())).isGreaterThanOrEqualTo(1600);
        assertThat(screenshot.mediaType()).isEqualTo(BrowserScreenshot.PNG_MEDIA_TYPE);
    }

    @Test
    void fillsScrollsAndCapturesLocatorEvidenceWithOperationTimeout() {
        service.navigate(pageUri, Duration.ofSeconds(15)).join();

        service.fill("#input", "Agent4J", Duration.ofSeconds(15)).join();
        service.scroll(500, Duration.ofSeconds(15)).join();
        BrowserEvidence evidence = service.capture(
                BrowserEvidenceSelector.locator("#result"), Duration.ofSeconds(15)).join();

        assertThat(evidence.selector()).isEqualTo("#result");
        assertThat(evidence.finalUrl()).isEqualTo(pageUri);
        assertThat(evidence.dom()).contains("result");
        assertThat(evidence.visibleText()).isEqualTo("result");
        assertThat(evidence.screenshot().pngBytes()).startsWith(PNG_SIGNATURE);
    }

    @Test
    void rejectsInvalidArgumentsSynchronously() {
        assertThatThrownBy(() -> service.navigate(
                URI.create("/relative"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
        assertThatThrownBy(() -> service.navigate(
                URI.create("file:///tmp/page.html"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
        assertThatThrownBy(() -> service.navigate(pageUri, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> service.screenshot(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> service.click(" ", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selector");
        assertThatThrownBy(() -> service.fill("#input", "value", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> service.capture(
                BrowserEvidenceSelector.page(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> service.scroll(1, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 毫秒");
        assertThatThrownBy(() -> service.extractDom(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void reportsMissingSelectorAsAsynchronousBrowserFailure() {
        service.navigate(pageUri, Duration.ofSeconds(15)).join();

        assertThatThrownBy(() -> service.click(
                "#missing", Duration.ofMillis(100)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(BrowserAutomationException.class);
    }

    @Test
    void rejectsEveryOperationAfterCloseAndAllowsRepeatedClose() {
        service.close();

        assertClosedFailure(service.navigate(pageUri, Duration.ofSeconds(1))::join);
        assertClosedFailure(service.click("#change", Duration.ofSeconds(1))::join);
        assertClosedFailure(service.fill("#input", "value", Duration.ofSeconds(1))::join);
        assertClosedFailure(service.scroll(100, Duration.ofSeconds(1))::join);
        assertClosedFailure(service.extractDom(Duration.ofSeconds(1))::join);
        assertClosedFailure(service.screenshot(Duration.ofSeconds(1))::join);
        assertClosedFailure(service.capture(
                BrowserEvidenceSelector.page(), Duration.ofSeconds(1))::join);
        assertThatCode(service::close).doesNotThrowAnyException();
    }

    @Test
    void retriesCleanupAfterFailureAndBoundsEachCleanupWait() {
        AtomicInteger attempts = new AtomicInteger();
        PlaywrightBrowserService retrying = new PlaywrightBrowserService(
                () -> attempts.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(
                                new IllegalStateException("first cleanup failed"))
                        : CompletableFuture.completedFuture(null),
                Duration.ofMillis(100));

        assertThatThrownBy(retrying::close)
                .isInstanceOf(BrowserAutomationException.class)
                .hasMessageContaining("清理失败")
                .hasRootCauseMessage("first cleanup failed");

        assertThatCode(retrying::close).doesNotThrowAnyException();
        assertThat(attempts).hasValue(2);

        CompletableFuture<Void> blockedCleanup = new CompletableFuture<>();
        AtomicInteger blockedAttempts = new AtomicInteger();
        PlaywrightBrowserService bounded = new PlaywrightBrowserService(
                () -> blockedAttempts.incrementAndGet() == 1
                        ? blockedCleanup
                        : CompletableFuture.completedFuture(null),
                Duration.ofMillis(20));

        assertThatThrownBy(bounded::close)
                .isInstanceOf(BrowserAutomationException.class)
                .hasMessageContaining("超时");
        assertThatCode(bounded::close).doesNotThrowAnyException();
        assertThat(blockedAttempts).hasValue(2);
    }

    @Test
    void doesNotStartAnotherCleanupWhileTimedOutCleanupIsStillRunning() {
        NonCancellableFuture blockedCleanup = new NonCancellableFuture();
        AtomicInteger attempts = new AtomicInteger();
        PlaywrightBrowserService service = new PlaywrightBrowserService(
                () -> attempts.incrementAndGet() == 1
                        ? blockedCleanup
                        : CompletableFuture.completedFuture(null),
                Duration.ofMillis(20));

        assertThatThrownBy(service::close)
                .isInstanceOf(BrowserAutomationException.class)
                .hasMessageContaining("超时");
        assertThatThrownBy(service::close)
                .isInstanceOf(BrowserAutomationException.class)
                .hasMessageContaining("仍在进行");
        assertThat(attempts).hasValue(1);

        blockedCleanup.complete(null);
        assertThatCode(service::close).doesNotThrowAnyException();
        assertThat(attempts).hasValue(2);
    }

    private static final class NonCancellableFuture extends CompletableFuture<Void> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    private void servePage(HttpExchange exchange) throws IOException {
        byte[] response = ("<!doctype html><html><body>"
                + "<button id=\"change\" onclick=\"document.querySelector('#state').textContent='after'\">change</button>"
                + "<input id=\"input\" value=\"before\">"
                + "<div id=\"result\">result</div>"
                + "<div id=\"state\">before</div>"
                + "<div style=\"height:1600px\">full page content</div>"
                + "</body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (exchange; var body = exchange.getResponseBody()) {
            body.write(response);
        }
    }

    private static int readPngHeight(byte[] pngBytes) {
        return ByteBuffer.wrap(pngBytes, 20, Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt();
    }

    private static void assertClosedFailure(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(BrowserAutomationException.class)
                .hasMessageContaining("关闭");
    }
}
