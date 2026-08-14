package com.agent.web.controller;

import com.agent.core.command.CommandDefinition;
import com.agent.core.command.CommandParameter;
import java.util.List;
import java.util.Objects;

/** Slash Command Registry 的只读 HTTP 视图。 */
public record CommandView(
        String name,
        String displayName,
        String description,
        List<String> aliases,
        List<CommandParameterView> parameters,
        String channel,
        String source,
        String permission) {

    /** 从核心命令定义创建前端视图。 */
    public static CommandView from(CommandDefinition definition) {
        Objects.requireNonNull(definition, "definition 不能为空");
        return new CommandView(
                definition.name(),
                definition.displayName(),
                definition.description(),
                definition.aliases(),
                definition.parameters().stream().map(CommandParameterView::from).toList(),
                definition.channel().name(),
                definition.source().name(),
                definition.permission().name());
    }

    /** 参数视图。 */
    public record CommandParameterView(String name, String description, boolean required) {
        static CommandParameterView from(CommandParameter parameter) {
            return new CommandParameterView(parameter.name(), parameter.description(), parameter.required());
        }
    }
}
