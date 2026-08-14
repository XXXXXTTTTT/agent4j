package com.agent.core.command;

/** 精确 Checkpoint 回滚端口。 */
@FunctionalInterface
public interface CommandCheckpointService {

    /** 恢复同一工作区和会话内的精确 checkpoint。 */
    CommandResult rewind(CommandContext context, String checkpoint);
}
