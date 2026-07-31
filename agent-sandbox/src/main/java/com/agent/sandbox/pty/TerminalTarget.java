package com.agent.sandbox.pty;

/** 命令执行目标。 */
public sealed interface TerminalTarget permits DockerTarget, PtyTarget {
}
