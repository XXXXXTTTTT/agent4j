package com.agent.core.command;

/** Markdown 命令定义无法安全加载时抛出的异常。 */
public final class MarkdownCommandLoadException extends RuntimeException {

    /** 创建带文件上下文的加载异常。 */
    public MarkdownCommandLoadException(String message) {
        super(message);
    }

    /** 创建带原始异常的加载异常。 */
    public MarkdownCommandLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
