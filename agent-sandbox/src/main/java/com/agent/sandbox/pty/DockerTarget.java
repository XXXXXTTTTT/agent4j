package com.agent.sandbox.pty;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Docker 容器执行目标。
 *
 * @param image              镜像名称
 * @param hostWorkspace      宿主工作目录
 * @param containerWorkspace 容器工作目录
 */
public record DockerTarget(
        String image,
        Path hostWorkspace,
        String containerWorkspace) implements TerminalTarget {

    /** 创建并校验 Docker 目标。 */
    public DockerTarget {
        image = requireText(image, "image 不能为空");
        containerWorkspace = requireText(containerWorkspace, "containerWorkspace 不能为空");
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
}
