package com.agent.core.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCommandHandlerTest {

    @Test
    void rendersArgumentAndCallsWorkflowBridgeExactlyOnce() {
        AtomicReference<String> rendered = new AtomicReference<>();
        WorkflowCommandHandler handler = new WorkflowCommandHandler(
                "为 ${request} 制定计划",
                List.of(new CommandParameter("request", "用户请求", true)),
                new CommandTemplateRenderer(),
                (invocation, context, template) -> {
                    rendered.set(template);
                    return CommandResult.forwarded("已提交工作流");
                });

        CommandResult result = handler.handle(
                new CommandInvocation("plan", List.of("修复登录"), "/plan 修复登录"),
                new CommandContext("actor-1", "workspace-1", "conversation-1"));

        assertThat(result.status()).isEqualTo(CommandResult.Status.FORWARDED);
        assertThat(rendered).hasValue("为 修复登录 制定计划");
    }
}
