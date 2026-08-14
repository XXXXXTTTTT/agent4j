package com.agent.core.command;

/** Slash Command 的执行通道。 */
public enum CommandChannel {
    /** 本地同步控制，不得触发模型调用。 */
    SYSTEM_DIRECTIVE,
    /** 经过模板和权限治理后进入 Agent 工作流。 */
    WORKFLOW_SKILL
}
