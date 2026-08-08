package com.agent.core.cli;

/** CLI 命令定义非法。 */
public final class CliCommandDefinitionException extends IllegalArgumentException {

    private final String commandName;

    /** 创建带命令名的定义异常。 */
    public CliCommandDefinitionException(String commandName, String message) {
        super(message);
        this.commandName = commandName;
    }

    /** 创建并保留底层异常。 */
    public CliCommandDefinitionException(String commandName, String message, Throwable cause) {
        super(message, cause);
        this.commandName = commandName;
    }

    public String commandName() {
        return commandName;
    }
}
