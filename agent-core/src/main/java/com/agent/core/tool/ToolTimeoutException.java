package com.agent.core.tool;

import java.time.Duration;
import java.util.Objects;

/** 工具执行超过定义的超时。 */
public final class ToolTimeoutException extends ToolException {

    private final String toolName;
    private final Duration timeout;

    public ToolTimeoutException(String toolName, Duration timeout) {
        super("工具执行超时: " + requireToolName(toolName));
        this.toolName = toolName;
        this.timeout = requireTimeout(timeout);
    }

    public String toolName() {
        return toolName;
    }

    public Duration timeout() {
        return timeout;
    }

    private static String requireToolName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        return value;
    }

    private static Duration requireTimeout(Duration value) {
        Objects.requireNonNull(value, "timeout 不能为空");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
        return value;
    }
}
