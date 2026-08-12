package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.WorkspaceMountMode;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** MCP Docker 运行的不可变启动规范。 */
public record McpDockerLaunchSpec(
        UUID installationId,
        UUID snapshotId,
        String image,
        String command,
        List<String> arguments,
        String containerWorkingDirectory,
        WorkspaceMountMode workspaceMountMode,
        String networkMode,
        long memoryBytes,
        long nanoCpus,
        long pidsLimit,
        int maxStdoutFrameBytes,
        int maxStdoutBufferedBytes,
        int maxStderrBytes,
        Set<String> environmentVariableNames) {

    public McpDockerLaunchSpec {
        if (installationId == null || snapshotId == null
                || image == null || image.isBlank()
                || command == null || command.isBlank()) {
            throw new IllegalArgumentException("启动标识、镜像和命令不能为空");
        }
        arguments = List.copyOf(arguments == null ? List.of() : arguments);
        if (containerWorkingDirectory == null || containerWorkingDirectory.isBlank()) {
            throw new IllegalArgumentException("containerWorkingDirectory 不能为空");
        }
        workspaceMountMode = workspaceMountMode == null
                ? WorkspaceMountMode.NONE : workspaceMountMode;
        if (!"none".equals(networkMode)) {
            throw new IllegalArgumentException("networkMode 只允许 none");
        }
        if (memoryBytes <= 0 || nanoCpus <= 0 || pidsLimit <= 0
                || maxStdoutFrameBytes <= 0 || maxStdoutBufferedBytes <= 0
                || maxStderrBytes <= 0) {
            throw new IllegalArgumentException("资源与输出限制必须为正数");
        }
        environmentVariableNames = Set.copyOf(
                environmentVariableNames == null ? Set.of() : environmentVariableNames);
    }
}
