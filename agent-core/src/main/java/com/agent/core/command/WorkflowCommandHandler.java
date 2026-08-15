package com.agent.core.command;

import java.util.List;
import java.util.Objects;

/** 将 Markdown 正文渲染后提交到既有工作流桥接端口。 */
public final class WorkflowCommandHandler implements WorkflowPromptCommandHandler {

    private final String template;
    private final List<CommandParameter> parameters;
    private final CommandTemplateRenderer renderer;
    private final WorkflowCommandBridge bridge;

    /** 创建一个不可执行 Shell 的模板工作流 Handler。 */
    public WorkflowCommandHandler(
            String template,
            List<CommandParameter> parameters,
            CommandTemplateRenderer renderer,
            WorkflowCommandBridge bridge) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("template 不能为空");
        }
        this.template = template;
        this.parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters 不能为空"));
        this.renderer = Objects.requireNonNull(renderer, "renderer 不能为空");
        this.bridge = Objects.requireNonNull(bridge, "bridge 不能为空");
    }

    @Override
    public CommandResult handle(CommandInvocation invocation, CommandContext context) {
        return bridge.submit(invocation, context, renderPrompt(invocation, context));
    }

    @Override
    public String renderPrompt(CommandInvocation invocation, CommandContext context) {
        return renderer.render(template, parameters, invocation, context);
    }

    @Override
    public WorkflowCommandBridge workflowBridge() {
        return bridge;
    }
}
