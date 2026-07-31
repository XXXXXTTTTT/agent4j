package com.agent.sandbox.pty;

import java.util.Objects;

/**
 * Bash 命令结果。
 *
 * @param exitCode 退出码，超时时固定为 -1
 * @param stdout   标准输出
 * @param stderr   标准错误
 * @param timedOut 是否超时
 */
public record CommandResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut) {

    /** 创建并校验结果。 */
    public CommandResult {
        stdout = Objects.requireNonNull(stdout, "stdout 不能为空");
        stderr = Objects.requireNonNull(stderr, "stderr 不能为空");
        if (timedOut && exitCode != -1) {
            throw new IllegalArgumentException("超时结果的 exitCode 必须为 -1");
        }
    }
}
