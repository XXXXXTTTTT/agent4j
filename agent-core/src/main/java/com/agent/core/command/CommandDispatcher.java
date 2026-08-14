package com.agent.core.command;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** 负责解析、精确路由、授权、执行和审计的双通道分发器。 */
public final class CommandDispatcher {

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
}
