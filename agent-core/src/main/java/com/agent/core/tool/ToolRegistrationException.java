package com.agent.core.tool;

/** 工具定义注册失败。 */
public final class ToolRegistrationException extends ToolException {

    private final String toolName;

    public ToolRegistrationException(String toolName, String message, Throwable cause) {
        super(message, cause);
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
