package com.agent.web.terminal;

import com.agent.sandbox.pty.InteractivePtySession;
import com.agent.sandbox.pty.PtyTarget;
import com.agent.web.config.ProductionAgentProperties;
import com.agent.web.workspace.WorkspaceRecord;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** 为授权工作区创建与当前服务容器绑定的交互式 PTY 会话。 */
public final class WorkspaceInteractiveTerminalService {

    private final Path shellExecutable;

    /** 使用生产配置指定的 shell 可执行文件创建服务。 */
    public WorkspaceInteractiveTerminalService(ProductionAgentProperties properties) {
        Objects.requireNonNull(properties, "properties 不能为空");
        this.shellExecutable = Path.of(properties.bashExecutable());
    }

    /** 在已完成访问校验的工作区目录中启动一个独立 shell。 */
    public InteractivePtySession open(
            WorkspaceRecord workspace,
            Consumer<String> outputConsumer,
            Consumer<Integer> exitConsumer) {
        Objects.requireNonNull(workspace, "workspace 不能为空");
        return InteractivePtySession.start(
                new PtyTarget(shellExecutable, workspace.workspacePath()),
                outputConsumer,
                exitConsumer);
    }
}
