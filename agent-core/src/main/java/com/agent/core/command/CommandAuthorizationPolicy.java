package com.agent.core.command;

/** 命令执行前的权限策略端口。 */
@FunctionalInterface
public interface CommandAuthorizationPolicy {

    /** 判断调用者是否满足命令最低权限。 */
    CommandAuthorizationDecision authorize(CommandDefinition definition, CommandContext context);
}
