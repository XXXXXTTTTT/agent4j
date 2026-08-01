package com.agent.sandbox.browser;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** 异步浏览器自动化协议。 */
public interface BrowserAutomation extends AutoCloseable {

    /**
     * 导航到精确 URL。
     *
     * @param url     目标 URL
     * @param timeout 导航超时时间
     * @return 异步导航结果
     */
    CompletableFuture<NavigationResult> navigate(URI url, Duration timeout);

    /**
     * 点击精确选择器定位的元素。
     *
     * @param selector Playwright 选择器
     * @param timeout  点击超时时间
     * @return 异步完成信号
     */
    CompletableFuture<Void> click(String selector, Duration timeout);

    /**
     * 提取当前页面的完整 DOM。
     *
     * @return 异步 DOM 字符串
     */
    CompletableFuture<String> extractDom();

    /**
     * 截取当前完整页面。
     *
     * @param timeout 截图超时时间
     * @return 异步 PNG 截图
     */
    CompletableFuture<BrowserScreenshot> screenshot(Duration timeout);

    /** 关闭浏览器资源。 */
    @Override
    void close();
}
