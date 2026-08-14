package com.agent.core.command;

import java.util.Objects;

/** 命令位置参数元数据。 */
public record CommandParameter(String name, String description, boolean required) {

    /** 校验参数元数据。 */
    public CommandParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("参数名称不能为空");
        }
        if (name.contains(" ") || name.contains("/") || name.contains("$")) {
            throw new IllegalArgumentException("参数名称包含非法字符: " + name);
        }
        description = Objects.requireNonNullElse(description, "");
    }
}
