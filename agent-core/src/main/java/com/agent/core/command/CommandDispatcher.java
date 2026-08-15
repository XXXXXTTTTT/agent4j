package com.agent.core.command;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 负责解析、精确路由、授权、执行和审计的双通道分发器。 */
public final class CommandDispatcher {

    private static final int MAX_STACKED_WORKFLOW_SKILLS = 6;

    private final SlashCommandParser parser;
    private final CommandRegistry registry;
    private final CommandAuthorizationPolicy authorizationPolicy;
    private final CommandAuditSink auditSink;
    private final Clock clock;

    /** 创建使用 UTC 时钟的分发器。 */
    public CommandDispatcher(
            CommandRegistry registry,
            CommandAuthorizationPolicy authorizationPolicy,
            CommandAuditSink auditSink) {
        this(registry, authorizationPolicy, auditSink, Clock.systemUTC());
    }

    /** 创建可注入时钟的分发器。 */
    public CommandDispatcher(
            CommandRegistry registry,
            CommandAuthorizationPolicy authorizationPolicy,
            CommandAuditSink auditSink,
            Clock clock) {
        this.parser = new SlashCommandParser();
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.authorizationPolicy = Objects.requireNonNull(
                authorizationPolicy, "authorizationPolicy 不能为空");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 分发一次原始 Slash Command 输入。 */
    public CommandResult dispatch(String rawInput, CommandContext context) {
        Objects.requireNonNull(context, "context 不能为空");
        final CommandInvocation invocation;
        try {
            invocation = parser.parse(rawInput);
        } catch (CommandParseException exception) {
            return audit(context, null, CommandResult.failure(
                    CommandResult.Status.INVALID, exception.getMessage()));
        }
        CommandDefinition definition = registry.find(invocation.name()).orElse(null);
        if (definition == null) {
            return audit(context, invocation.name(), CommandResult.failure(
                    CommandResult.Status.NOT_FOUND, "未找到命令: " + invocation.name()));
        }
        StackedWorkflow stackedWorkflow = findStackedWorkflow(invocation, definition);
        if (stackedWorkflow != null) {
            return dispatchStackedWorkflow(stackedWorkflow, context);
        }
        return dispatchSingle(invocation, definition, context);
    }

    private CommandResult dispatchSingle(
            CommandInvocation invocation,
            CommandDefinition definition,
            CommandContext context) {
        if (invocation.arguments().size() < requiredParameterCount(definition)
                || invocation.arguments().size() > definition.parameters().size()) {
            return audit(context, definition.name(), CommandResult.failure(
                    CommandResult.Status.INVALID, "命令参数数量不合法: " + definition.name()));
        }
        CommandAuthorizationDecision decision = authorizationPolicy.authorize(definition, context);
        if (!decision.allowed()) {
            return audit(context, definition.name(), CommandResult.failure(
                    CommandResult.Status.DENIED, decision.reason()));
        }
        try {
            return audit(context, definition.name(), definition.handler()
                    .handle(invocation, context).withCommandName(definition.name()));
        } catch (RuntimeException exception) {
            return audit(context, definition.name(), CommandResult.failure(
                    CommandResult.Status.FAILED, exception.getMessage()));
        }
    }

    private StackedWorkflow findStackedWorkflow(
            CommandInvocation invocation,
            CommandDefinition firstDefinition) {
        if (firstDefinition.channel() != CommandChannel.WORKFLOW_SKILL
                || !(firstDefinition.handler() instanceof WorkflowPromptCommandHandler firstHandler)) {
            return null;
        }
        List<CommandDefinition> definitions = new ArrayList<>(List.of(firstDefinition));
        List<String> arguments = invocation.arguments();
        int argumentIndex = 0;
        while (argumentIndex < arguments.size() && arguments.get(argumentIndex).startsWith("/")) {
            String requestedName = arguments.get(argumentIndex).substring(1);
            CommandDefinition nextDefinition = registry.find(requestedName).orElse(null);
            if (nextDefinition == null || nextDefinition.channel() != CommandChannel.WORKFLOW_SKILL
                    || !(nextDefinition.handler() instanceof WorkflowPromptCommandHandler)) {
                break;
            }
            definitions.add(nextDefinition);
            argumentIndex++;
        }
        if (definitions.size() == 1) {
            return null;
        }
        if (definitions.size() > MAX_STACKED_WORKFLOW_SKILLS) {
            return new StackedWorkflow(
                    definitions, List.of(), invocation.rawInput(), firstHandler.workflowBridge(), true);
        }
        List<String> tailArguments = arguments.subList(argumentIndex, arguments.size());
        return new StackedWorkflow(
                definitions, tailArguments, invocation.rawInput(), firstHandler.workflowBridge(), false);
    }

    private CommandResult dispatchStackedWorkflow(StackedWorkflow stackedWorkflow, CommandContext context) {
        CommandDefinition firstDefinition = stackedWorkflow.definitions().getFirst();
        if (stackedWorkflow.exceedsMaximum()) {
            return audit(context, firstDefinition.name(), CommandResult.failure(
                    CommandResult.Status.INVALID, "连续工作流命令不能超过 " + MAX_STACKED_WORKFLOW_SKILLS + " 个"));
        }
        List<String> renderedPrompts = new ArrayList<>(stackedWorkflow.definitions().size());
        for (CommandDefinition definition : stackedWorkflow.definitions()) {
            if (!(definition.handler() instanceof WorkflowPromptCommandHandler handler)
                    || handler.workflowBridge() != stackedWorkflow.workflowBridge()) {
                return audit(context, firstDefinition.name(), CommandResult.failure(
                        CommandResult.Status.INVALID, "连续工作流命令必须使用同一提交桥接器"));
            }
            CommandInvocation invocation = new CommandInvocation(
                    definition.name(), stackedWorkflow.tailArguments(), stackedWorkflow.rawInput());
            if (invocation.arguments().size() < requiredParameterCount(definition)
                    || invocation.arguments().size() > definition.parameters().size()) {
                return audit(context, firstDefinition.name(), CommandResult.failure(
                        CommandResult.Status.INVALID, "命令参数数量不合法: " + definition.name()));
            }
            CommandAuthorizationDecision decision = authorizationPolicy.authorize(definition, context);
            if (!decision.allowed()) {
                return audit(context, firstDefinition.name(), CommandResult.failure(
                        CommandResult.Status.DENIED, decision.reason()));
            }
            try {
                renderedPrompts.add(handler.renderPrompt(invocation, context));
            } catch (RuntimeException exception) {
                return audit(context, firstDefinition.name(), CommandResult.failure(
                        CommandResult.Status.FAILED, exception.getMessage()));
            }
        }
        CommandInvocation firstInvocation = new CommandInvocation(
                firstDefinition.name(), stackedWorkflow.tailArguments(), stackedWorkflow.rawInput());
        try {
            return audit(context, firstDefinition.name(), stackedWorkflow.workflowBridge()
                    .submit(firstInvocation, context, String.join("\n\n", renderedPrompts))
                    .withCommandName(firstDefinition.name()));
        } catch (RuntimeException exception) {
            return audit(context, firstDefinition.name(), CommandResult.failure(
                    CommandResult.Status.FAILED, exception.getMessage()));
        }
    }

    private int requiredParameterCount(CommandDefinition definition) {
        return (int) definition.parameters().stream().filter(CommandParameter::required).count();
    }

    private CommandResult audit(CommandContext context, String commandName, CommandResult result) {
        String exactName = commandName == null ? "<invalid>" : commandName;
        Instant now = clock.instant();
        auditSink.record(new CommandAuditEvent(
                now,
                context.actorId(),
                context.workspaceId(),
                context.conversationId(),
                exactName,
                result.status(),
                result.message()));
        return result.withCommandName(commandName);
    }

    private record StackedWorkflow(
            List<CommandDefinition> definitions,
            List<String> tailArguments,
            String rawInput,
            WorkflowCommandBridge workflowBridge,
            boolean exceedsMaximum) {

        private StackedWorkflow {
            definitions = List.copyOf(definitions);
            tailArguments = List.copyOf(tailArguments);
            if (rawInput == null || rawInput.isBlank()) {
                throw new IllegalArgumentException("rawInput 不能为空");
            }
            Objects.requireNonNull(workflowBridge, "workflowBridge 不能为空");
        }
    }
}
