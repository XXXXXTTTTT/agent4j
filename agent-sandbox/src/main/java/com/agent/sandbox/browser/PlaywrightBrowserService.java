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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 在专属单虚拟线程中管理 Playwright Chromium 的浏览器服务。
 */
public final class PlaywrightBrowserService implements BrowserAutomation {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("playwright-browser-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private Playwright playwright;
    private Browser browser;
    private BrowserContext browserContext;
    private Page page;

    /**
     * 异步导航到绝对 HTTP 或 HTTPS URL。
     */
    @Override
    public CompletableFuture<NavigationResult> navigate(URI url, Duration timeout) {
        validateUrl(url);
        long timeoutMillis = validateTimeout(timeout);
        return submit("页面导航", () -> {
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
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("selector 不能为空");
        }
        long timeoutMillis = validateTimeout(timeout);
        return submit("页面点击", () -> {
            page.locator(selector).click(
                    new Locator.ClickOptions().setTimeout(timeoutMillis));
            return null;
        });
    }

    /**
     * 异步提取当前页面的完整 DOM。
     */
    @Override
    public CompletableFuture<String> extractDom() {
        return submit("DOM 提取", () -> page.content());
    }

    /**
     * 异步截取完整页面 PNG。
     */
    @Override
    public CompletableFuture<BrowserScreenshot> screenshot(Duration timeout) {
        long timeoutMillis = validateTimeout(timeout);
        return submit("页面截图", () -> new BrowserScreenshot(
                page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(true)
                        .setType(ScreenshotType.PNG)
                        .setTimeout(timeoutMillis)),
                BrowserScreenshot.PNG_MEDIA_TYPE));
    }

    /**
     * 在 Playwright 所属线程中按依赖顺序关闭全部资源。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture<Void> cleanup;
        try {
            cleanup = CompletableFuture.runAsync(this::closeResources, executor);
        } catch (RejectedExecutionException exception) {
            executor.close();
            throw new BrowserAutomationException("提交浏览器清理任务失败", exception);
        }

        try {
            cleanup.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BrowserAutomationException("等待浏览器资源清理时被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof BrowserAutomationException browserException) {
                throw browserException;
            }
            throw new BrowserAutomationException("浏览器资源清理失败", cause);
        } finally {
            executor.close();
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
        failure = closeResource(page, "Page", Page::close, failure);
        page = null;
        failure = closeResource(
                browserContext, "BrowserContext", BrowserContext::close, failure);
        browserContext = null;
        failure = closeResource(browser, "Browser", Browser::close, failure);
        browser = null;
        failure = closeResource(playwright, "Playwright", Playwright::close, failure);
        playwright = null;
        if (failure != null) {
            throw failure;
        }
    }

    private static <T> BrowserAutomationException closeResource(
            T resource,
            String resourceName,
            ResourceCloser<T> closer,
            BrowserAutomationException previousFailure) {
        if (resource == null) {
            return previousFailure;
        }
        try {
            closer.close(resource);
            return previousFailure;
        } catch (RuntimeException exception) {
            if (previousFailure == null) {
                return new BrowserAutomationException(
                        "关闭 " + resourceName + " 失败", exception);
            }
            previousFailure.addSuppressed(exception);
            return previousFailure;
        }
    }

    private static void validateUrl(URI url) {
        Objects.requireNonNull(url, "url 不能为空");
        String scheme = url.getScheme();
        if (!url.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("url 必须是绝对 http 或 https URI");
        }
    }

    private static long validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
        return timeout.toMillis();
    }

    @FunctionalInterface
    private interface ResourceCloser<T> {
        void close(T resource);
    }
}
