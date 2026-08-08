package com.agent.core.tool;

/** 工具调用需要人工审批。 */
public final class ToolApprovalRequiredException extends ToolException {

    private final String toolName;
    private final String reason;

    public ToolApprovalRequiredException(String toolName, String reason) {
        super("工具需要审批: " + require(toolName, "toolName") + ", "
                + require(reason, "reason"));
        this.toolName = toolName;
        this.reason = reason;
    }

    public String toolName() {
        return toolName;
    }

    public String reason() {
        return reason;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
