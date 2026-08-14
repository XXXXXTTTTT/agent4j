package com.agent.core.command;

import java.util.List;
import java.util.Objects;

/** 注册表中的不可变命令定义。 */
public record CommandDefinition(
        String name,
        String displayName,
        String description,
        List<String> aliases,
        List<CommandParameter> parameters,
        CommandChannel channel,
        CommandSource source,
        CommandPermission permission,
        CommandHandler handler) {

    /** 校验并冻结命令定义。 */
    public CommandDefinition {
        requireName(name, "name");
        displayName = requireText(displayName, "displayName");
        description = requireText(description, "description");
        aliases = freezeAliases(aliases);
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters 不能为空"));
        if (parameters.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("parameters 不能包含 null");
        }
        channel = Objects.requireNonNull(channel, "channel 不能为空");
        source = Objects.requireNonNull(source, "source 不能为空");
        permission = Objects.requireNonNull(permission, "permission 不能为空");
        handler = Objects.requireNonNull(handler, "handler 不能为空");
    }

    private static List<String> freezeAliases(List<String> values) {
        Objects.requireNonNull(values, "aliases 不能为空");
        for (String alias : values) {
            requireName(alias, "alias");
        }
        return List.copyOf(values);
    }

    private static void requireName(String value, String name) {
        requireText(value, name);
        if (value.charAt(0) == '/' || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " 包含非法字符: " + value);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
