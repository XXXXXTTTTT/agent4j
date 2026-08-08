package com.agent.core.cli;

/** CLI 意图参数非法。 */
public final class CliArgumentException extends IllegalArgumentException {

    private final String commandName;
    private final int argumentIndex;

    /** 创建带命令名和参数下标的异常。 */
    public CliArgumentException(String commandName, int argumentIndex, String message) {
        super(message);
        this.commandName = commandName;
        this.argumentIndex = argumentIndex;
    }

    /** 创建并保留底层异常。 */
    public CliArgumentException(
            String commandName,
            int argumentIndex,
            String message,
            Throwable cause) {
        super(message, cause);
        this.commandName = commandName;
        this.argumentIndex = argumentIndex;
    }

    public String commandName() {
        return commandName;
    }

    public int argumentIndex() {
        return argumentIndex;
    }
}
