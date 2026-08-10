package com.agent.core.cli;

import com.agent.sandbox.pty.TerminalTarget;

import java.nio.file.Path;

/** 按本轮真实工作区目录解析终端执行目标。 */
@FunctionalInterface
public interface WorkspaceTerminalTargetResolver {

    /** 返回与精确工作区目录绑定的执行目标。 */
    TerminalTarget resolve(Path workspacePath);
}
