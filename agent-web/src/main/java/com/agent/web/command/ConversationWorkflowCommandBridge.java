package com.agent.web.command;

import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandInvocation;
import com.agent.core.command.CommandResult;
import com.agent.core.command.WorkflowCommandBridge;
import com.agent.web.audit.ConversationAuditEvent;
import com.agent.web.audit.ConversationAuditEventType;
import com.agent.web.audit.ConversationAuditSink;
import com.agent.web.conversation.ConversationService;
import com.agent.web.conversation.ConversationTurnRecord;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 将命令正文提交到现有持久化会话服务并写入业务审计。 */
public final class ConversationWorkflowCommandBridge implements WorkflowCommandBridge {

    private final ConversationService conversationService;
    private final ConversationAuditSink auditSink;
    private final Clock clock;

    /** 创建使用 UTC 时钟的会话桥接器。 */
    public ConversationWorkflowCommandBridge(
            ConversationService conversationService,
            ConversationAuditSink auditSink) {
        this(conversationService, auditSink, Clock.systemUTC());
    }

    /** 创建可注入时钟的会话桥接器。 */
    public ConversationWorkflowCommandBridge(
            ConversationService conversationService,
            ConversationAuditSink auditSink,
            Clock clock) {
        this.conversationService = Objects.requireNonNull(
                conversationService, "conversationService 不能为空");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public CommandResult submit(
            CommandInvocation invocation,
            CommandContext context,
            String renderedTemplate) {
        UUID conversationId = parseUuid(context.conversationId(), "conversationId");
        String modelGroupId = context.variables().getOrDefault("modelGroupId", "");
        ConversationTurnRecord turn = conversationService.submitTurn(
                conversationId, renderedTemplate, null,
                modelGroupId.isBlank() ? null : modelGroupId);
        auditSink.record(new ConversationAuditEvent(
                ConversationAuditEventType.CONVERSATION_TURN_SUBMITTED,
                clock.instant(),
                context.actorId(),
                parseUuid(context.workspaceId(), "workspaceId"),
                conversationId,
                turn == null ? null : turn.turnId(),
                turn == null ? null : turn.runId(),
                turn == null ? null : turn.turnIndex(),
                CommandResult.Status.FORWARDED.name(),
                invocation.rawInput(),
                null,
                null,
                null));
        Map<String, Object> data = turn == null
                ? Map.of()
                : Map.of("turnId", turn.turnId(), "runId", turn.runId());
        return new CommandResult(
                CommandResult.Status.FORWARDED,
                invocation.name(),
                "已提交工作流命令",
                data);
    }

    private UUID parseUuid(String value, String name) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " 必须是 UUID", exception);
        }
    }
}
