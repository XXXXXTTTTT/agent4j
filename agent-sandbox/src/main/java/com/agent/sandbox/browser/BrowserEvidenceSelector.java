package com.agent.sandbox.browser;

import java.util.Objects;

/**
 * 浏览器证据采集范围。
 *
 * @param selector 精确的 page 标识或 Playwright locator
 */
public record BrowserEvidenceSelector(String selector) {

    public static final String PAGE_SELECTOR = "page";
    public static final int MAX_LOCATOR_CODE_POINTS = 2_048;

    /** 校验证据选择器。 */
    public BrowserEvidenceSelector {
        Objects.requireNonNull(selector, "selector 不能为空");
        if (selector.isBlank()) {
            throw new IllegalArgumentException("selector 不能为空");
        }
        if (selector.codePointCount(0, selector.length()) > MAX_LOCATOR_CODE_POINTS) {
            throw new IllegalArgumentException("selector 不能超过 2048 个 Unicode 码点");
        }
    }

    /** 创建完整页面证据选择器。 */
    public static BrowserEvidenceSelector page() {
        return new BrowserEvidenceSelector(PAGE_SELECTOR);
    }

    /** 创建元素证据选择器。 */
    public static BrowserEvidenceSelector locator(String selector) {
        return new BrowserEvidenceSelector(selector);
    }

    /** 判断是否采集完整页面。 */
    public boolean isPage() {
        return PAGE_SELECTOR.equals(selector);
    }
}
