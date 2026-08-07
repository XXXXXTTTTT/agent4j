package com.agent.web.validation;

import java.net.URI;

/** 统一校验 Reviewer 浏览器导航地址。 */
public final class ReviewerUrlValidator {

    private ReviewerUrlValidator() {
    }

    /** 校验可选地址并返回去除首尾空白后的值。 */
    public static String validateOptional(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String exact = value.trim();
        URI uri = URI.create(exact);
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("reviewerUrl 必须是绝对 HTTP/HTTPS URI");
        }
        return exact;
    }
}
