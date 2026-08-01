package com.agent.sandbox.browser;

import java.net.URI;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 页面导航结果。
 *
 * @param requestedUrl 请求 URL
 * @param finalUrl     最终 URL
 * @param statusCode   可选 HTTP 状态码
 */
public record NavigationResult(
        URI requestedUrl,
        URI finalUrl,
        OptionalInt statusCode) {

    /** 校验导航结果。 */
    public NavigationResult {
        Objects.requireNonNull(requestedUrl, "requestedUrl 不能为空");
        Objects.requireNonNull(finalUrl, "finalUrl 不能为空");
        Objects.requireNonNull(statusCode, "statusCode 不能为空");
    }
}
