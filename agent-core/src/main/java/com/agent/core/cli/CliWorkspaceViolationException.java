package com.agent.core.cli;

import java.nio.file.Path;

/** CLI 工作区边界非法。 */
public final class CliWorkspaceViolationException extends IllegalArgumentException {

    private final Path workspaceRoot;
    private final Path targetPath;

    /** 创建带工作区和目标路径的异常。 */
    public CliWorkspaceViolationException(Path workspaceRoot, Path targetPath, String message) {
        super(message);
        this.workspaceRoot = workspaceRoot;
        this.targetPath = targetPath;
    }

    /** 创建并保留底层异常。 */
    public CliWorkspaceViolationException(
            Path workspaceRoot,
            Path targetPath,
            String message,
            Throwable cause) {
        super(message, cause);
        this.workspaceRoot = workspaceRoot;
        this.targetPath = targetPath;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path targetPath() {
        return targetPath;
    }
}
