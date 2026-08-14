package com.agent.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 内置系统控制命令定义工厂；Dispatcher 不依赖具体命令名称。 */
public final class SystemCommandHandlers {

    private SystemCommandHandlers() {
    }

    /** 构造当前 Registry 所需的本地控制命令定义。 */
    public static List<CommandDefinition> definitions(
            CommandRegistry registry,
            CommandContextService contextService,
            CommandCheckpointService checkpointService) {
        Objects.requireNonNull(registry, "registry 不能为空");
        Objects.requireNonNull(contextService, "contextService 不能为空");
        Objects.requireNonNull(checkpointService, "checkpointService 不能为空");
        List<CommandDefinition> definitions = new ArrayList<>();
        definitions.add(definition("help", "帮助", "列出当前已注册命令", List.of(),
                CommandPermission.VIEWER,
                (invocation, context) -> {
                    String query = invocation.arguments().isEmpty()
                            ? "" : invocation.arguments().getFirst();
                    String message = registry.search(query).stream()
                            .map(CommandDefinition::name)
                            .sorted()
                            .reduce((left, right) -> left + "\n" + right)
                            .orElse("暂无可用命令");
                    return CommandResult.success(message);
                }));
        definitions.add(definition("context", "上下文", "显示当前上下文统计", List.of(),
                CommandPermission.VIEWER, (invocation, context) -> contextService.context(context)));
        definitions.add(definition("compact", "压缩", "使用本地策略压缩会话上下文",
                List.of(new CommandParameter("focus", "压缩重点", false)),
                CommandPermission.OPERATOR,
                (invocation, context) -> contextService.compact(
                        context, invocation.arguments().isEmpty() ? "" : invocation.arguments().getFirst())));
        definitions.add(definition("clear", "清空", "创建同工作区的新会话", List.of(),
                CommandPermission.OPERATOR, (invocation, context) -> contextService.clear(context)));
        definitions.add(definition("cost", "费用", "显示当前会话模型调用统计", List.of(),
                CommandPermission.VIEWER, (invocation, context) -> contextService.cost(context)));
        definitions.add(definition("permissions", "权限", "读取或更新命令权限", List.of(
                        new CommandParameter("action", "权限操作", false)),
                CommandPermission.VIEWER, (invocation, context) -> contextService.permissions(
                        context, invocation.arguments())));
        definitions.add(definition("rewind", "回滚", "回滚到精确 Checkpoint",
                List.of(new CommandParameter("checkpoint", "Checkpoint 标识", true)),
                CommandPermission.OPERATOR,
                (invocation, context) -> checkpointService.rewind(
                        context, invocation.arguments().getFirst())));
        return List.copyOf(definitions);
    }

    private static CommandDefinition definition(
            String name,
            String displayName,
            String description,
            List<CommandParameter> parameters,
            CommandPermission permission,
            CommandHandler handler) {
        return new CommandDefinition(
                name, displayName, description, List.of(), parameters,
                CommandChannel.SYSTEM_DIRECTIVE, CommandSource.BUILT_IN, permission, handler);
    }
}
