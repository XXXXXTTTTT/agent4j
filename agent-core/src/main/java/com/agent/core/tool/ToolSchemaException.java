package com.agent.core.tool;

/** JSON Schema 或工具参数校验失败。 */
public final class ToolSchemaException extends ToolException {

    private final String jsonPointer;

    public ToolSchemaException(String jsonPointer, String message, Throwable cause) {
        super(message, cause);
        if (jsonPointer == null || jsonPointer.isBlank()) {
            throw new IllegalArgumentException("jsonPointer 不能为空");
        }
        this.jsonPointer = jsonPointer;
    }

    public String jsonPointer() {
        return jsonPointer;
    }
}
