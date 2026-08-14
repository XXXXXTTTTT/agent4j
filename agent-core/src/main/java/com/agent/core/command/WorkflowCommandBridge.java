package com.agent.core.command;

/** 将已渲染工作流模板接入既有 Agent 会话/Graph 的端口。 */
@FunctionalInterface
public interface WorkflowCommandBridge {

    /** 提交渲染后的工作流请求。 */
    CommandResult submit(CommandInvocation invocation, CommandContext context, String renderedTemplate);
}
