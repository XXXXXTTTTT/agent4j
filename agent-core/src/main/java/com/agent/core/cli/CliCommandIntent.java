package com.agent.core.cli;

import com.agent.sandbox.pty.TerminalTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Agent 生成的结构化 CLI 命令意图。 */
public record CliCommandIntent(
        String name,
        List<String> arguments,
        Path workspaceRoot,
        TerminalTarget target,
        Duration timeout) {

    private static final int MAX_ARGUMENTS = 64;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /** 校验意图的结构和资源上限。 */
    public CliCommandIntent {
        if (!CliValidation.isDefinitionName(name)) {
            throw new CliArgumentException(name, -1, "命令名格式非法");
        }
        List<String> argumentSnapshot = new java.util.ArrayList<>(
                Objects.requireNonNull(arguments, "arguments 不能为空"));
        for (int index = 0; index < argumentSnapshot.size(); index++) {
            if (argumentSnapshot.get(index) == null) {
                throw new CliArgumentException(name, index, "参数不能为 null");
            }
        }
        arguments = List.copyOf(argumentSnapshot);
        if (arguments.size() > MAX_ARGUMENTS) {
            throw new CliArgumentException(name, -1, "用户参数数量不能超过 " + MAX_ARGUMENTS);
        }
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            throw new CliWorkspaceViolationException(workspaceRoot, null, "workspaceRoot 必须是现有目录");
        }
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        target = Objects.requireNonNull(target, "target 不能为空");
        timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new CliArgumentException(name, -1, "timeout 必须大于 0 且不超过 10 分钟");
        }
    }
}
