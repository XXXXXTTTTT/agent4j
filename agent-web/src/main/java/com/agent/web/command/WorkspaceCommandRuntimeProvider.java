package com.agent.web.command;

import com.agent.core.command.CommandDispatcher;
import com.agent.core.command.CommandRegistry;
import com.agent.web.workspace.WorkspaceRecord;

import java.util.Objects;

/** 按工作区提供已加载命令注册表和分发器。 */
@FunctionalInterface
public interface WorkspaceCommandRuntimeProvider {

    /** 解析工作区对应的命令运行时。 */
    Runtime resolve(WorkspaceRecord workspace);

    /** 工作区命令运行时快照。 */
    record Runtime(CommandRegistry registry, CommandDispatcher dispatcher) {
        public Runtime {
            Objects.requireNonNull(registry, "registry 不能为空");
            Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        }
    }
}
