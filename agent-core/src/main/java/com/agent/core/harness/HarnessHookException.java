package com.agent.core.harness;

import java.util.Objects;

/** 保存 Hook 身份、事件类型和原始 cause 的强类型异常。 */
public final class HarnessHookException extends RuntimeException {

    private final String hookName;
    private final HarnessEventType eventType;

    /** 包装单个 Hook 处理失败。 */
    public HarnessHookException(
            String hookName,
            HarnessEventType eventType,
            RuntimeException cause) {
        super("Harness Hook 执行失败: " + requireText(hookName)
                + ", eventType=" + Objects.requireNonNull(eventType, "eventType 不能为空"),
                Objects.requireNonNull(cause, "cause 不能为空"));
        this.hookName = hookName;
        this.eventType = eventType;
    }

    public String hookName() {
        return hookName;
    }

    public HarnessEventType eventType() {
        return eventType;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("hookName 不能为空");
        }
        return value;
    }
}
