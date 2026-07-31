package com.agent.sandbox.pty;

import java.time.Duration;
import java.util.Objects;

/**
 * Bash 命令请求。
 *
 * @param target      执行目标
 * @param bashCommand Bash 命令
 * @param timeout     超时时间
 */
public record CommandRequest(
        TerminalTarget target,
        String bashCommand,
        Duration timeout) {

    /** 创建并校验请求。 */
    public CommandRequest {
        target = Objects.requireNonNull(target, "target 不能为空");
        if (bashCommand == null || bashCommand.isBlank()) {
            throw new IllegalArgumentException("bashCommand 不能为空");
        }
        timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }
}
