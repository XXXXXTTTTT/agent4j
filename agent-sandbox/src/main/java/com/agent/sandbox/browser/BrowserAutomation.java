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
     * 向精确选择器定位的输入控件填充值。
     *
     * @param selector Playwright 选择器
     * @param value    精确输入值
     * @param timeout  填充超时时间
     * @return 异步完成信号
     */
    default CompletableFuture<Void> fill(
            String selector,
            String value,
            Duration timeout) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("浏览器实现不支持 fill"));
    }

    /**
     * 垂直滚动当前页面。
     *
     * @param deltaY  垂直滚动量
     * @param timeout 操作超时时间
     * @return 异步完成信号
     */
    default CompletableFuture<Void> scroll(int deltaY, Duration timeout) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("浏览器实现不支持 scroll"));
    }

    /**
     * 采集完整页面或指定元素证据。
     *
     * @param selector 证据选择器
     * @param timeout  采集超时时间
     * @return 异步证据
     */
    default CompletableFuture<BrowserEvidence> capture(
            BrowserEvidenceSelector selector,
            Duration timeout) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("浏览器实现不支持 capture"));
    }

    /**
     * 提取当前页面的完整 DOM（兼容旧版协议）。
     *
     * @return 异步 DOM 字符串
     */
    CompletableFuture<String> extractDom();

    /**
     * 提取当前页面的完整 DOM。
     *
     * @param timeout DOM 提取超时时间
     * @return 异步 DOM 字符串
     */
    CompletableFuture<String> extractDom(Duration timeout);

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
