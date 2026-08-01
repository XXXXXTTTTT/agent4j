package com.agent.sandbox.browser;

import java.util.Objects;

/**
 * PNG 浏览器截图。
 *
 * @param pngBytes  PNG 字节
 * @param mediaType 精确媒体类型
 */
public record BrowserScreenshot(byte[] pngBytes, String mediaType) {

    public static final String PNG_MEDIA_TYPE = "image/png";

    /** 校验并复制截图字节。 */
    public BrowserScreenshot {
        Objects.requireNonNull(pngBytes, "pngBytes 不能为空");
        if (pngBytes.length == 0) {
            throw new IllegalArgumentException("pngBytes 不能为空字节");
        }
        if (!PNG_MEDIA_TYPE.equals(mediaType)) {
            throw new IllegalArgumentException("mediaType 必须是 image/png");
        }
        pngBytes = pngBytes.clone();
    }

    /** 返回截图字节副本。 */
    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }
}
