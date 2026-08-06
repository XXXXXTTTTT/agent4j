package com.agent.sandbox.pty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Docker 容器执行目标。
 *
 * @param image              镜像名称
 * @param hostWorkspace      宿主工作目录
 * @param containerWorkspace 容器工作目录
 * @param workspaceSource    Docker Engine 可见的工作区来源
 */
public record DockerTarget(
        String image,
        Path hostWorkspace,
        String containerWorkspace,
        WorkspaceSource workspaceSource) implements TerminalTarget {

    /** 使用当前进程可见的宿主工作区创建 Docker 目标。 */
    public DockerTarget(
            String image,
            Path hostWorkspace,
            String containerWorkspace) {
        this(image, hostWorkspace, containerWorkspace, new HostWorkspaceSource());
    }

    /** 创建并校验 Docker 目标。 */
    public DockerTarget {
        image = requireText(image, "image 不能为空");
        containerWorkspace = requireText(containerWorkspace, "containerWorkspace 不能为空");
        Objects.requireNonNull(workspaceSource, "workspaceSource 不能为空");
        if (hostWorkspace == null || !Files.isDirectory(hostWorkspace)) {
            throw new IllegalArgumentException("hostWorkspace 必须是现有目录");
        }
        hostWorkspace = hostWorkspace.toAbsolutePath().normalize();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /** Docker 工作区来源只允许宿主目录或另一个容器的精确 bind mount。 */
    public sealed interface WorkspaceSource
            permits HostWorkspaceSource, ContainerWorkspaceSource {
    }

    /** 直接使用当前进程可见宿主目录的工作区来源。 */
    public record HostWorkspaceSource() implements WorkspaceSource {
    }

    /** 从指定容器的精确工作区路径解析 Docker Engine bind source。 */
    public record ContainerWorkspaceSource(
            String containerName,
            String containerPath) implements WorkspaceSource {

        /** 校验源容器名与容器内绝对路径。 */
        public ContainerWorkspaceSource {
            containerName = requireText(containerName, "containerName 不能为空");
            containerPath = requireText(containerPath, "containerPath 不能为空");
            if (!containerPath.startsWith("/")) {
                throw new IllegalArgumentException("containerPath 必须是绝对路径");
            }
        }
    }
}
