package com.agent.sandbox.pty;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地 PTY 执行目标。
 *
 * @param bashExecutable Bash 可执行文件
 * @param workingDirectory 工作目录
 */
public record PtyTarget(Path bashExecutable, Path workingDirectory) implements TerminalTarget {

    /** 创建并校验 PTY 目标。 */
    public PtyTarget {
        if (bashExecutable == null || !Files.isRegularFile(bashExecutable)) {
            throw new IllegalArgumentException("bashExecutable 必须是现有普通文件");
        }
        if (workingDirectory == null || !Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException("workingDirectory 必须是现有目录");
        }
        bashExecutable = bashExecutable.toAbsolutePath().normalize();
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }
}
