package com.agent.core.command;

/** 为可串联工作流命令提供提示词渲染与提交端口。 */
public interface WorkflowPromptCommandHandler extends CommandHandler {

    /** 使用当前调用和上下文渲染单个工作流提示词。 */
    String renderPrompt(CommandInvocation invocation, CommandContext context);

    /** 返回负责创建 Agent Run 的工作流桥接器。 */
    WorkflowCommandBridge workflowBridge();
}
