package com.agent.core.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        definitions.add(definition("help", "帮助", "列出当前已注册命令",
                List.of(), List.of(new CommandParameter("query", "命令查询", false)),
                CommandPermission.VIEWER,
                (invocation, context) -> {
                    String query = invocation.arguments().isEmpty()
                            ? "" : invocation.arguments().getFirst();
                    List<CommandDefinition> matches = registry.search(query).stream()
                            .sorted(java.util.Comparator.comparing(CommandDefinition::name))
                            .toList();
                    String message = matches.stream()
                            .map(CommandDefinition::name)
                            .reduce((left, right) -> left + "\n" + right)
                            .orElse("暂无可用命令");
                    return new CommandResult(CommandResult.Status.COMPLETED, null, message, Map.of(
                            "query", query,
                            "count", matches.size(),
                            "commands", matches.stream()
                                    .map(SystemCommandHandlers::commandView)
                                    .toList()));
                }));
        definitions.add(definition("context", "上下文", "显示当前上下文统计", List.of(),
                CommandPermission.VIEWER, (invocation, context) -> contextService.context(context)));
        definitions.add(definition("status", "状态", "显示当前会话执行边界", List.of(),
                CommandPermission.VIEWER, (invocation, context) -> new CommandResult(
                        CommandResult.Status.COMPLETED, null, "会话状态", java.util.Map.of(
                                "actorId", context.actorId(),
                                "workspaceId", context.workspaceId(),
                                "conversationId", context.conversationId()))));
        definitions.add(definition("memory", "记忆", "生成当前会话的本地上下文摘要", List.of(),
                CommandPermission.VIEWER, (invocation, context) -> contextService.compact(context, "memory")));
        definitions.add(definition("compact", "压缩", "使用本地策略压缩会话上下文",
                List.of(new CommandParameter("focus", "压缩重点", false)),
                CommandPermission.OPERATOR,
                (invocation, context) -> contextService.compact(
                        context, invocation.arguments().isEmpty() ? "" : invocation.arguments().getFirst())));
        definitions.add(definition("clear", "清空", "创建同工作区的新会话", List.of(),
                CommandPermission.OPERATOR, (invocation, context) -> contextService.clear(context)));
        definitions.add(definition("new", "新会话", "创建同工作区的新会话", List.of(),
                CommandPermission.OPERATOR, (invocation, context) -> contextService.clear(context)));
        definitions.add(definition("reset", "重置", "创建同工作区的新会话", List.of(),
                CommandPermission.OPERATOR, (invocation, context) -> contextService.clear(context)));
        definitions.add(definition("cost", "费用", "显示当前会话模型调用统计", List.of("usage"), List.of(),
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

    private static Map<String, Object> commandView(CommandDefinition definition) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", definition.name());
        view.put("displayName", definition.displayName());
        view.put("description", definition.description());
        view.put("aliases", definition.aliases());
        view.put("parameters", definition.parameters().stream()
                .map(parameter -> Map.of(
                        "name", parameter.name(),
                        "description", parameter.description(),
                        "required", parameter.required()))
                .toList());
        view.put("channel", definition.channel().name());
        view.put("source", definition.source().name());
        view.put("permission", definition.permission().name());
        return Map.copyOf(view);
    }

    private static CommandDefinition definition(
            String name,
            String displayName,
            String description,
            List<String> aliases,
            List<CommandParameter> parameters,
            CommandPermission permission,
            CommandHandler handler) {
        return new CommandDefinition(
                name, displayName, description, aliases, parameters,
                CommandChannel.SYSTEM_DIRECTIVE, CommandSource.BUILT_IN, permission, handler);
    }

    private static CommandDefinition definition(
            String name,
            String displayName,
            String description,
            List<CommandParameter> parameters,
            CommandPermission permission,
            CommandHandler handler) {
        return definition(name, displayName, description, List.of(), parameters, permission, handler);
    }
}
