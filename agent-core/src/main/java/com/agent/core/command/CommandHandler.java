package com.agent.core.command;

/** 可注册命令的执行函数。 */
@FunctionalInterface
public interface CommandHandler {

    /** 执行已经完成解析和授权的命令。 */
    CommandResult handle(CommandInvocation invocation, CommandContext context);
}
