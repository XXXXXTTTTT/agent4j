package com.agent.core.command;

import java.util.List;
import java.util.Objects;

/** 经过 Slash Command 词法解析的不可变调用。 */
public record CommandInvocation(String name, List<String> arguments, String rawInput) {

    /** 校验并冻结解析结果。 */
    public CommandInvocation {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("命令名称不能为空");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments 不能为空"));
        if (arguments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("arguments 不能包含 null");
        }
        if (rawInput == null || rawInput.isBlank()) {
            throw new IllegalArgumentException("rawInput 不能为空");
        }
    }
}
