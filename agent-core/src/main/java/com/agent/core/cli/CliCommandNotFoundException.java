package com.agent.core.cli;

/** CLI 命令名未注册。 */
public final class CliCommandNotFoundException extends IllegalArgumentException {

    private final String commandName;

    /** 创建精确命令名未找到异常。 */
    public CliCommandNotFoundException(String commandName) {
        super("CLI 命令未注册: " + commandName);
        this.commandName = commandName;
    }

    public String commandName() {
        return commandName;
    }
}
