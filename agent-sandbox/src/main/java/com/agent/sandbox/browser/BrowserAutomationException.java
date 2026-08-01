package com.agent.sandbox.browser;

/** 浏览器初始化、操作或清理失败。 */
public final class BrowserAutomationException extends RuntimeException {

    /** 使用错误消息创建异常。 */
    public BrowserAutomationException(String message) {
        super(message);
    }

    /** 使用错误消息与原始异常创建异常。 */
    public BrowserAutomationException(String message, Throwable cause) {
        super(message, cause);
    }
}
