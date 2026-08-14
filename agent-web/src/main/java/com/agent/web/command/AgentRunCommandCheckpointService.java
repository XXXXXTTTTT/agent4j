package com.agent.web.command;

import com.agent.core.command.CommandCheckpointService;
import com.agent.core.command.CommandContext;
import com.agent.core.command.CommandResult;
import com.agent.core.engine.AgentRunService;
import com.agent.core.engine.RunCheckpoint;
import com.agent.web.audit.ConversationAuditSink;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将命令中的精确 Checkpoint 引用绑定到当前会话和工作区。 */
public final class AgentRunCommandCheckpointService implements CommandCheckpointService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunCommandCheckpointService.class);

    private static final String WORKSPACE_KEY = "conversation.workspaceId";
    private static final String CONVERSATION_KEY = "conversation.id";

    private final AgentRunService runService;
    private final ConversationAuditSink auditSink;

    /** 创建 Checkpoint 命令服务。 */
    public AgentRunCommandCheckpointService(
            AgentRunService runService,
            ConversationAuditSink auditSink) {
        this.runService = Objects.requireNonNull(runService, "runService 不能为空");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
    }

    @Override
    public CommandResult rewind(CommandContext context, String checkpoint) {
        Objects.requireNonNull(context, "context 不能为空");
        CheckpointReference reference;
        try {
            reference = CheckpointReference.parse(checkpoint);
        } catch (IllegalArgumentException exception) {
            return CommandResult.failure(CommandResult.Status.INVALID, exception.getMessage());
        }

        List<RunCheckpoint> history;
        try {
            history = runService.history(reference.runId());
        } catch (RuntimeException exception) {
            return CommandResult.failure(CommandResult.Status.NOT_FOUND, "Checkpoint 不存在");
        }
        RunCheckpoint target = history.stream()
                .filter(item -> item.version() == reference.version())
                .findFirst()
                .orElse(null);
        if (target == null) {
            return CommandResult.failure(CommandResult.Status.NOT_FOUND, "Checkpoint 不存在");
        }
        if (!context.workspaceId().equals(target.state().variables().get(WORKSPACE_KEY))) {
            return CommandResult.failure(CommandResult.Status.DENIED, "Checkpoint 不属于当前工作区");
        }
        if (!context.conversationId().equals(target.state().variables().get(CONVERSATION_KEY))) {
            return CommandResult.failure(CommandResult.Status.DENIED, "Checkpoint 不属于当前会话");
        }

        try {
            RunCheckpoint restored = runService.rewind(reference.runId(), reference.version());
            try {
                auditSink.record(new com.agent.web.audit.ConversationAuditEvent(
                    com.agent.web.audit.ConversationAuditEventType.CHECKPOINT_REWOUND,
                    java.time.Instant.now(),
                    context.actorId(),
                    parseUuid(context.workspaceId(), "workspaceId"),
                    parseUuid(context.conversationId(), "conversationId"),
                    null,
                    restored.runId(),
                    null,
                    restored.status().name(),
                    "" + checkpoint,
                    null,
                    null,
                    null));
            } catch (RuntimeException auditFailure) {
                LOGGER.warn("Checkpoint 回滚成功但审计写入失败 runId={} version={}",
                        restored.runId(), restored.version(), auditFailure);
            }
            return new CommandResult(
                    CommandResult.Status.COMPLETED,
                    "rewind",
                    "已回滚到 Checkpoint " + reference.version(),
                    java.util.Map.of("runId", restored.runId(), "version", restored.version(),
                            "status", restored.status().name()));
        } catch (RuntimeException exception) {
            return CommandResult.failure(CommandResult.Status.FAILED, "Checkpoint 回滚失败");
        }
    }

    private UUID parseUuid(String value, String name) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " 必须是 UUID", exception);
        }
    }

    private record CheckpointReference(UUID runId, long version) {
        private static CheckpointReference parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Checkpoint 引用不能为空");
            }
            int separator = value.indexOf(':');
            if (separator <= 0 || separator != value.lastIndexOf(':')
                    || separator == value.length() - 1) {
                throw new IllegalArgumentException("Checkpoint 引用必须是 runId:version");
            }
            UUID runId;
            long version;
            try {
                runId = UUID.fromString(value.substring(0, separator));
                version = Long.parseLong(value.substring(separator + 1));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Checkpoint 引用必须是 runId:version", exception);
            }
            if (version < 0) {
                throw new IllegalArgumentException("Checkpoint version 不能小于 0");
            }
            return new CheckpointReference(runId, version);
        }
    }
}
