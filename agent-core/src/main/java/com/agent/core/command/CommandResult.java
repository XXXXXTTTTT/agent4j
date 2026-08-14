package com.agent.core.command;

import java.util.Map;
import java.util.Objects;

/** 命令分发的结构化结果。 */
public record CommandResult(
        Status status,
        String commandName,
        String message,
        Map<String, Object> data) {

    /** 命令生命周期状态。 */
    public enum Status {
        COMPLETED,
        FORWARDED,
        INVALID,
        NOT_FOUND,
        DENIED,
        FAILED
    }

    /** 校验并冻结结果。 */
    public CommandResult {
        status = Objects.requireNonNull(status, "status 不能为空");
        message = Objects.requireNonNullElse(message, "");
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** 创建本地成功结果。 */
    public static CommandResult success(String message) {
        return new CommandResult(Status.COMPLETED, null, message, Map.of());
    }

    /** 创建带状态和稳定错误消息的结果。 */
    public static CommandResult failure(Status status, String message) {
        if (status == Status.COMPLETED || status == Status.FORWARDED) {
            throw new IllegalArgumentException("failure status 不能是成功状态");
        }
        return new CommandResult(status, null, message, Map.of());
    }

    /** 给结果补充命令名称。 */
    public CommandResult withCommandName(String value) {
        return new CommandResult(status, value, message, data);
    }
}
