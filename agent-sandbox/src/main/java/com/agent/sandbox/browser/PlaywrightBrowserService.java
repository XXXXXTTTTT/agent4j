package com.agent.sandbox.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.ScreenshotType;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 在专属单虚拟线程中管理 Playwright Chromium 的浏览器服务。
 */
public final class PlaywrightBrowserService implements BrowserAutomation {

    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("playwright-browser-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cleanupRunning = new AtomicBoolean();
    private final Supplier<CompletableFuture<Void>> cleanupTaskSupplier;
    private final Duration closeTimeout;
    private CompletableFuture<Void> cleanupInFlight;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext browserContext;
    private Page page;

    /** 创建使用真实 Playwright 资源清理逻辑的浏览器服务。 */
    public PlaywrightBrowserService() {
        this(null, DEFAULT_CLOSE_TIMEOUT);
    }

    PlaywrightBrowserService(
            Supplier<CompletableFuture<Void>> cleanupTaskSupplier,
            Duration closeTimeout) {
        this.cleanupTaskSupplier = cleanupTaskSupplier;
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout 不能为空");
        validateTimeout(closeTimeout);
    }

    /**
     * 异步导航到绝对 HTTP 或 HTTPS URL。
     */
    @Override
    public CompletableFuture<NavigationResult> navigate(URI url, Duration timeout) {
        validateUrl(url);
        long timeoutMillis = validateTimeout(timeout);
        return submit("页面导航", () -> {
            applyOperationTimeout(timeoutMillis);
            Response response = page.navigate(
                    url.toString(),
                    new Page.NavigateOptions().setTimeout(timeoutMillis));
            OptionalInt statusCode = response == null
                    ? OptionalInt.empty()
                    : OptionalInt.of(response.status());
            return new NavigationResult(url, URI.create(page.url()), statusCode);
        });
    }

    /**
     * 异步点击精确传入的 Playwright 选择器。
     */
    @Override
    public CompletableFuture<Void> click(String selector, Duration timeout) {
        validateSelector(selector);
        long timeoutMillis = validateTimeout(timeout);
        return submit("页面点击", () -> {
            applyOperationTimeout(timeoutMillis);
            page.locator(selector).click(
                    new Locator.ClickOptions().setTimeout(timeoutMillis));
            return null;
        });
    }

    /**
     * 异步填充精确传入的 Playwright 选择器。
     */
    @Override
    public CompletableFuture<Void> fill(
            String selector,
            String value,
            Duration timeout) {
        validateSelector(selector);
        Objects.requireNonNull(value, "value 不能为空");
        long timeoutMillis = validateTimeout(timeout);
        return submit("页面填充", () -> {
            applyOperationTimeout(timeoutMillis);
            page.locator(selector).fill(
                    value,
                    new Locator.FillOptions().setTimeout(timeoutMillis));
            return null;
        });
    }

    /**
     * 异步垂直滚动当前页面。
     */
    @Override
    public CompletableFuture<Void> scroll(int deltaY, Duration timeout) {
        long timeoutMillis = validateTimeout(timeout);
        return submit("页面滚动", () -> {
            applyOperationTimeout(timeoutMillis);
            page.locator("html").evaluate(
                    "(element, amount) => window.scrollBy(0, amount)",
                    deltaY,
                    new Locator.EvaluateOptions().setTimeout(timeoutMillis));
            return null;
        });
    }

    /**
     * 异步采集完整页面或指定元素证据。
     */
    @Override
    public CompletableFuture<BrowserEvidence> capture(
            BrowserEvidenceSelector selector,
            Duration timeout) {
        Objects.requireNonNull(selector, "selector 不能为空");
        long timeoutMillis = validateTimeout(timeout);
        return submit("页面证据采集", () -> {
            applyOperationTimeout(timeoutMillis);
            return selector.isPage()
                    ? capturePage(selector, timeoutMillis)
                    : captureLocator(selector, timeoutMillis);
        });
    }

    /**
     * 异步提取当前页面的完整 DOM。
     */
    @Override
    public CompletableFuture<String> extractDom() {
        return submit("DOM 提取", () -> Objects.toString(page.locator("html").evaluate(
                "element => element.outerHTML")));
    }

    /**
     * 异步提取当前页面的完整 DOM，并应用操作级超时。
     */
    @Override
    public CompletableFuture<String> extractDom(Duration timeout) {
        long timeoutMillis = validateTimeout(timeout);
        return submit("DOM 提取", () -> {
            applyOperationTimeout(timeoutMillis);
            Object outerHtml = page.locator("html").evaluate(
                    "element => element.outerHTML",
                    null,
                    new Locator.EvaluateOptions().setTimeout(timeoutMillis));
            return Objects.toString(outerHtml);
        });
    }

    /**
     * 异步截取完整页面 PNG。
     */
    @Override
    public CompletableFuture<BrowserScreenshot> screenshot(Duration timeout) {
        long timeoutMillis = validateTimeout(timeout);
        return submit("页面截图", () -> {
            applyOperationTimeout(timeoutMillis);
            return new BrowserScreenshot(
                    page.screenshot(new Page.ScreenshotOptions()
                            .setFullPage(true)
                            .setType(ScreenshotType.PNG)
                            .setTimeout(timeoutMillis)),
                    BrowserScreenshot.PNG_MEDIA_TYPE);
        });
    }

    /**
     * 在 Playwright 所属线程中按依赖顺序关闭全部资源。
     */
    @Override
    public synchronized void close() {
        if (closed.get()) {
            return;
        }
        if (cleanupRunning.get()
                || (cleanupInFlight != null && !cleanupInFlight.isDone())) {
            throw new BrowserAutomationException("上一次浏览器清理仍在进行");
        }
        cleanupInFlight = null;

        CompletableFuture<Void> cleanup;
        try {
            cleanup = cleanupTaskSupplier == null
                    ? CompletableFuture.runAsync(() -> {
                        cleanupRunning.set(true);
                        try {
                            closeResources();
                        } finally {
                            cleanupRunning.set(false);
                        }
                    }, executor)
                    : Objects.requireNonNull(
                            cleanupTaskSupplier.get(),
                            "cleanupTaskSupplier 不得返回 null");
            cleanupInFlight = cleanup;
        } catch (RejectedExecutionException exception) {
            throw new BrowserAutomationException("提交浏览器清理任务失败", exception);
        }

        try {
            cleanup.get(closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
            cleanupInFlight = null;
            closed.set(true);
            executor.close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BrowserAutomationException("等待浏览器资源清理时被中断", exception);
        } catch (TimeoutException exception) {
            cleanup.cancel(true);
            if (cleanup.isDone() && !cleanupRunning.get()) {
                cleanupInFlight = null;
            }
            throw new BrowserAutomationException("等待浏览器资源清理超时", exception);
        } catch (ExecutionException exception) {
            cleanupInFlight = null;
            Throwable cause = exception.getCause();
            if (cause instanceof BrowserAutomationException browserException) {
                throw browserException;
            }
            throw new BrowserAutomationException("浏览器资源清理失败", cause);
        }
    }

    private <T> CompletableFuture<T> submit(String operation, Supplier<T> action) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new BrowserAutomationException("浏览器服务已关闭"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    ensureInitialized();
                    return action.get();
                } catch (RuntimeException exception) {
                    throw new BrowserAutomationException(operation + "失败", exception);
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(
                    new BrowserAutomationException("浏览器服务已关闭", exception));
        }
    }

    private void ensureInitialized() {
        if (page != null) {
            return;
        }
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        browserContext = browser.newContext();
        page = browserContext.newPage();
    }

    private void closeResources() {
        BrowserAutomationException failure = null;
        BrowserAutomationException closeFailure = closeResource(page, "Page", Page::close);
        if (closeFailure == null) {
            page = null;
        }
        failure = mergeFailure(failure, closeFailure);
        closeFailure = closeResource(
                browserContext, "BrowserContext", BrowserContext::close);
        if (closeFailure == null) {
            browserContext = null;
        }
        failure = mergeFailure(failure, closeFailure);
        closeFailure = closeResource(browser, "Browser", Browser::close);
        if (closeFailure == null) {
            browser = null;
        }
        failure = mergeFailure(failure, closeFailure);
        closeFailure = closeResource(playwright, "Playwright", Playwright::close);
        if (closeFailure == null) {
            playwright = null;
        }
        failure = mergeFailure(failure, closeFailure);
        if (failure != null) {
            throw failure;
        }
    }

    private static <T> BrowserAutomationException closeResource(
            T resource,
            String resourceName,
            ResourceCloser<T> closer) {
        if (resource == null) {
            return null;
        }
        try {
            closer.close(resource);
            return null;
        } catch (RuntimeException exception) {
            return new BrowserAutomationException(
                    "关闭 " + resourceName + " 失败", exception);
        }
    }

    private static BrowserAutomationException mergeFailure(
            BrowserAutomationException failure,
            BrowserAutomationException nextFailure) {
        if (nextFailure == null) {
            return failure;
        }
        if (failure == null) {
            return nextFailure;
        }
        failure.addSuppressed(nextFailure);
        return failure;
    }

    private static void validateUrl(URI url) {
        Objects.requireNonNull(url, "url 不能为空");
        String scheme = url.getScheme();
        if (!url.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("url 必须是绝对 http 或 https URI");
        }
    }

    private BrowserEvidence capturePage(
            BrowserEvidenceSelector selector,
            long timeoutMillis) {
        BrowserScreenshot capturedScreenshot = new BrowserScreenshot(
                page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(true)
                        .setType(ScreenshotType.PNG)
                        .setTimeout(timeoutMillis)),
                BrowserScreenshot.PNG_MEDIA_TYPE);
        Object outerHtml = page.locator("html").evaluate(
                "element => element.outerHTML",
                null,
                new Locator.EvaluateOptions().setTimeout(timeoutMillis));
        Object visibleText = page.locator("html").evaluate(
                "element => element.innerText",
                null,
                new Locator.EvaluateOptions().setTimeout(timeoutMillis));
        return new BrowserEvidence(
                URI.create(page.url()),
                selector.selector(),
                Objects.toString(outerHtml),
                Objects.toString(visibleText, ""),
                capturedScreenshot);
    }

    private BrowserEvidence captureLocator(
            BrowserEvidenceSelector selector,
            long timeoutMillis) {
        Locator locator = page.locator(selector.selector());
        locator.waitFor(new Locator.WaitForOptions().setTimeout(timeoutMillis));
        Object outerHtml = locator.evaluate(
                "element => element.outerHTML",
                null,
                new Locator.EvaluateOptions().setTimeout(timeoutMillis));
        Object visibleText = locator.evaluate(
                "element => element.innerText",
                null,
                new Locator.EvaluateOptions().setTimeout(timeoutMillis));
        BrowserScreenshot capturedScreenshot = new BrowserScreenshot(
                locator.screenshot(new Locator.ScreenshotOptions()
                        .setType(ScreenshotType.PNG)
                        .setTimeout(timeoutMillis)),
                BrowserScreenshot.PNG_MEDIA_TYPE);
        return new BrowserEvidence(
                URI.create(page.url()),
                selector.selector(),
                Objects.toString(outerHtml),
                Objects.toString(visibleText, ""),
                capturedScreenshot);
    }

    private static void validateSelector(String selector) {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("selector 不能为空");
        }
    }

    private static long validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
        long timeoutMillis = timeout.toMillis();
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeout 必须至少为 1 毫秒");
        }
        return timeoutMillis;
    }

    private void applyOperationTimeout(long timeoutMillis) {
        page.setDefaultTimeout(timeoutMillis);
        browserContext.setDefaultTimeout(timeoutMillis);
    }

    @FunctionalInterface
    private interface ResourceCloser<T> {
        void close(T resource);
    }
}
