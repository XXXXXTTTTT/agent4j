package com.agent.core.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CommandDispatcherTest {

    @Test
    void systemHandlerRunsLocallyAndReturnsCompletedResult() {
        AtomicInteger invocations = new AtomicInteger();
        CommandDefinition definition = definition("help", CommandChannel.SYSTEM_DIRECTIVE,
                (invocation, context) -> {
                    invocations.incrementAndGet();
                    return CommandResult.success("本地帮助");
                });
        CommandDispatcher dispatcher = dispatcher(List.of(definition),
                (command, context) -> CommandAuthorizationDecision.allow());

        CommandResult result = dispatcher.dispatch("/help", context());

        assertThat(result.status()).isEqualTo(CommandResult.Status.COMPLETED);
        assertThat(result.message()).isEqualTo("本地帮助");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void unknownAndDeniedCommandsDoNotInvokeTheirHandlers() {
        AtomicInteger invocations = new AtomicInteger();
        CommandDefinition definition = definition("plan", CommandChannel.WORKFLOW_SKILL,
                (invocation, context) -> {
                    invocations.incrementAndGet();
                    return CommandResult.success("不应执行");
                });
        CommandDispatcher dispatcher = dispatcher(List.of(definition),
                (command, context) -> CommandAuthorizationDecision.deny("权限不足"));

        assertThat(dispatcher.dispatch("/missing", context()).status())
                .isEqualTo(CommandResult.Status.NOT_FOUND);
        assertThat(dispatcher.dispatch("/plan", context()).status())
                .isEqualTo(CommandResult.Status.DENIED);
        assertThat(invocations).hasValue(0);
    }

    @Test
    void combinesStackedWorkflowSkillsIntoOneBridgeSubmission() {
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<String> rendered = new AtomicReference<>();
        AtomicReference<String> rawInput = new AtomicReference<>();
        WorkflowCommandBridge bridge = (invocation, context, prompt) -> {
            submissions.incrementAndGet();
            rendered.set(prompt);
            rawInput.set(invocation.rawInput());
            return CommandResult.forwarded("已提交组合工作流");
        };
        CommandParameter request = new CommandParameter("request", "工作请求", true);
        CommandTemplateRenderer renderer = new CommandTemplateRenderer();
        CommandDefinition plan = new CommandDefinition(
                "plan", "计划", "制定计划", List.of(), List.of(request),
                CommandChannel.WORKFLOW_SKILL, CommandSource.BUILT_IN,
                CommandPermission.VIEWER,
                new WorkflowCommandHandler("计划：${request}", List.of(request), renderer, bridge));
        CommandDefinition review = new CommandDefinition(
                "review", "审查", "执行审查", List.of(), List.of(request),
                CommandChannel.WORKFLOW_SKILL, CommandSource.BUILT_IN,
                CommandPermission.VIEWER,
                new WorkflowCommandHandler("审查：${request}", List.of(request), renderer, bridge));
        CommandDispatcher dispatcher = dispatcher(List.of(plan, review),
                (command, context) -> CommandAuthorizationDecision.allow());

        CommandResult result = dispatcher.dispatch("/plan /review \"修复登录\"", context());

        assertThat(result.status()).isEqualTo(CommandResult.Status.FORWARDED);
        assertThat(submissions).hasValue(1);
        assertThat(rendered.get()).isEqualTo("计划：修复登录\n\n审查：修复登录");
        assertThat(rawInput).hasValue("/plan /review \"修复登录\"");
    }

    @Test
    void doesNotSubmitStackedWorkflowWhenAnySkillIsDenied() {
        AtomicInteger submissions = new AtomicInteger();
        WorkflowCommandBridge bridge = (invocation, context, prompt) -> {
            submissions.incrementAndGet();
            return CommandResult.forwarded("不应提交");
        };
        CommandParameter request = new CommandParameter("request", "工作请求", true);
        CommandTemplateRenderer renderer = new CommandTemplateRenderer();
        CommandDefinition plan = workflowDefinition("plan", request, renderer, bridge);
        CommandDefinition review = workflowDefinition("review", request, renderer, bridge);
        CommandDispatcher dispatcher = dispatcher(List.of(plan, review),
                (command, context) -> command.name().equals("review")
                        ? CommandAuthorizationDecision.deny("缺少审查权限")
                        : CommandAuthorizationDecision.allow());

        CommandResult result = dispatcher.dispatch("/plan /review 修复登录", context());

        assertThat(result.status()).isEqualTo(CommandResult.Status.DENIED);
        assertThat(submissions).hasValue(0);
    }

    @Test
    void rejectsSeventhStackedWorkflowBeforeSubmitting() {
        AtomicInteger submissions = new AtomicInteger();
        WorkflowCommandBridge bridge = (invocation, context, prompt) -> {
            submissions.incrementAndGet();
            return CommandResult.forwarded("不应提交");
        };
        CommandParameter request = new CommandParameter("request", "工作请求", true);
        CommandTemplateRenderer renderer = new CommandTemplateRenderer();
        List<CommandDefinition> definitions = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> workflowDefinition("workflow" + index, request, renderer, bridge))
                .toList();
        CommandDispatcher dispatcher = dispatcher(definitions,
                (command, context) -> CommandAuthorizationDecision.allow());

        CommandResult result = dispatcher.dispatch(
                "/workflow1 /workflow2 /workflow3 /workflow4 /workflow5 /workflow6 /workflow7 修复登录",
                context());

        assertThat(result.status()).isEqualTo(CommandResult.Status.INVALID);
        assertThat(submissions).hasValue(0);
    }

    private CommandDefinition workflowDefinition(
            String name,
            CommandParameter request,
            CommandTemplateRenderer renderer,
            WorkflowCommandBridge bridge) {
        return new CommandDefinition(
                name, name, "测试工作流", List.of(), List.of(request),
                CommandChannel.WORKFLOW_SKILL, CommandSource.BUILT_IN,
                CommandPermission.VIEWER,
                new WorkflowCommandHandler(name + "：${request}", List.of(request), renderer, bridge));
    }

    private CommandDefinition definition(
            String name,
            CommandChannel channel,
            CommandHandler handler) {
        return new CommandDefinition(
                name,
                name,
                "测试命令",
                List.of(),
                List.of(),
                channel,
                CommandSource.BUILT_IN,
                CommandPermission.VIEWER,
                handler);
    }

    private CommandDispatcher dispatcher(
            List<CommandDefinition> definitions,
            CommandAuthorizationPolicy authorizationPolicy) {
        InMemoryCommandRegistry registry = new InMemoryCommandRegistry();
        registry.replace(definitions);
        return new CommandDispatcher(
                registry,
                authorizationPolicy,
                event -> { });
    }

    private CommandContext context() {
        return new CommandContext("actor-1", "workspace-1", "conversation-1");
    }
}
