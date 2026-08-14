package com.agent.core.command;

import java.util.List;

/** 系统控制命令所需的本地会话服务端口。 */
public interface CommandContextService {

    /** 返回当前上下文统计。 */
    CommandResult context(CommandContext context);

    /** 使用确定性策略压缩当前会话。 */
    CommandResult compact(CommandContext context, String focus);

    /** 在同一工作区创建新会话。 */
    CommandResult clear(CommandContext context);

    /** 返回当前模型调用统计。 */
    CommandResult cost(CommandContext context);

    /** 读取或更新当前命令权限。 */
    CommandResult permissions(CommandContext context, List<String> arguments);
}
