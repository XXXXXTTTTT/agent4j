package com.agent.web.terminal;

import com.agent.core.engine.RunCheckpoint;
import com.agent.core.nodes.OpsNode;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 最新 Checkpoint 中的终端结果快照。
 *
 * @param runId Run 标识
 * @param checkpointVersion Checkpoint 版本
 * @param stdout 标准输出
 * @param stderr 标准错误
 * @param exitCode 退出码
 * @param timedOut 是否超时
 * @param error 完整终端错误栈
 */
public record TerminalSnapshot(
        UUID runId,
        long checkpointVersion,
        String stdout,
        String stderr,
        Integer exitCode,
        Boolean timedOut,
        String error) {

    /** 校验终端快照。 */
    public TerminalSnapshot {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (checkpointVersion < 0) {
            throw new IllegalArgumentException("checkpointVersion 不能小于 0");
        }
        Objects.requireNonNull(stdout, "stdout 不能为空");
        Objects.requireNonNull(stderr, "stderr 不能为空");
    }

    /** 从 Checkpoint 的精确 Ops 状态键创建快照。 */
    public static TerminalSnapshot from(RunCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint 不能为空");
        Map<String, String> variables = checkpoint.state().variables();
        return new TerminalSnapshot(
                checkpoint.runId(),
                checkpoint.version(),
                variables.getOrDefault(OpsNode.STDOUT_KEY, ""),
                variables.getOrDefault(OpsNode.STDERR_KEY, ""),
                parseExitCode(variables.get(OpsNode.EXIT_CODE_KEY)),
                parseTimedOut(variables.get(OpsNode.TIMED_OUT_KEY)),
                variables.get(OpsNode.ERROR_KEY));
    }

    private static Integer parseExitCode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "状态变量 " + OpsNode.EXIT_CODE_KEY + " 不是整数: " + value,
                    exception);
        }
    }

    private static Boolean parseTimedOut(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(
                    "状态变量 " + OpsNode.TIMED_OUT_KEY + " 不是布尔值: " + value);
        };
    }
}
