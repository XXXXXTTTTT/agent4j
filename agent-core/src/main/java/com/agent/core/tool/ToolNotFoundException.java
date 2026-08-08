package com.agent.core.tool;

/** 工具名称未注册。 */
public final class ToolNotFoundException extends ToolException {

    private final String toolName;

    public ToolNotFoundException(String toolName) {
        super("工具未注册: " + requireToolName(toolName));
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }

    private static String requireToolName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        return value;
    }
}
