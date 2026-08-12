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
        String materialContainerDirectory,
        String materialSourceContainer,
        String materialSourcePath,
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
        if (command.startsWith("/") || command.contains("..") || command.contains("\\")) {
            throw new IllegalArgumentException("command 必须是无越界段的相对物料入口");
        }
        arguments = List.copyOf(arguments == null ? List.of() : arguments);
        materialContainerDirectory = requireText(materialContainerDirectory, "materialContainerDirectory");
        materialSourceContainer = materialSourceContainer == null ? "" : materialSourceContainer.trim();
        materialSourcePath = materialSourcePath == null ? "" : materialSourcePath.trim();
        if (materialSourceContainer.isBlank() != materialSourcePath.isBlank()) {
            throw new IllegalArgumentException("materialSourceContainer 与 materialSourcePath 必须同时配置");
        }
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

    /** 兼容既有 runner 单元测试；生产生命周期必须显式传递物料三字段。 */
    public McpDockerLaunchSpec(
            UUID installationId, UUID snapshotId, String image, String command, List<String> arguments,
            String containerWorkingDirectory, WorkspaceMountMode workspaceMountMode, String networkMode,
            long memoryBytes, long nanoCpus, long pidsLimit, int maxStdoutFrameBytes,
            int maxStdoutBufferedBytes, int maxStderrBytes, Set<String> environmentVariableNames) {
        this(installationId, snapshotId, image, command, arguments, "/mcp-material", "", "",
                containerWorkingDirectory, workspaceMountMode, networkMode, memoryBytes, nanoCpus, pidsLimit,
                maxStdoutFrameBytes, maxStdoutBufferedBytes, maxStderrBytes, environmentVariableNames);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
