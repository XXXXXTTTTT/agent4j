package com.agent.sandbox.browser;

import java.net.URI;
import java.util.Objects;

/**
 * 一次浏览器证据采集结果。
 *
 * @param finalUrl   采集时的最终 URL
 * @param selector   精确证据选择器
 * @param dom        页面或元素 DOM
 * @param screenshot PNG 截图
 */
public record BrowserEvidence(
        URI finalUrl,
        String selector,
        String dom,
        BrowserScreenshot screenshot) {

    /** 校验字段并复制截图。 */
    public BrowserEvidence {
        Objects.requireNonNull(finalUrl, "finalUrl 不能为空");
        Objects.requireNonNull(selector, "selector 不能为空");
        Objects.requireNonNull(dom, "dom 不能为空");
        Objects.requireNonNull(screenshot, "screenshot 不能为空");
        if (selector.isBlank()) {
            throw new IllegalArgumentException("selector 不能为空");
        }
        if (dom.isBlank()) {
            throw new IllegalArgumentException("dom 不能为空");
        }
        screenshot = new BrowserScreenshot(
                screenshot.pngBytes(), screenshot.mediaType());
    }

    /** 返回截图的防御性副本。 */
    @Override
    public BrowserScreenshot screenshot() {
        return new BrowserScreenshot(
                screenshot.pngBytes(), screenshot.mediaType());
    }
}
