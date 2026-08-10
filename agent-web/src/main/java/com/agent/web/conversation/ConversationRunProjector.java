package com.agent.web.conversation;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;
import com.agent.web.audit.ConversationAuditEvent;
import com.agent.web.audit.ConversationAuditEventType;
import com.agent.web.audit.ConversationAuditSink;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将 Run 终态幂等投影为会话轮次终态。 */
public final class ConversationRunProjector implements TraceEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationRunProjector.class);

    private final ConversationRepository repository;
    private final Checkpointer checkpointer;
    private final ConversationAuditSink auditSink;
    private final Clock clock;
    private final ConcurrentMap<UUID, Boolean> projected = new ConcurrentHashMap<>();

    /** 创建带 Checkpointer 的生产投影器。 */
    public ConversationRunProjector(
            ConversationRepository repository,
            Checkpointer checkpointer,
            Clock clock) {
        this(repository, checkpointer, ConversationAuditSink.noop(), clock);
    }

    /** 创建带 Checkpointer 和业务审计的生产投影器。 */
    public ConversationRunProjector(
            ConversationRepository repository,
            Checkpointer checkpointer,
            ConversationAuditSink auditSink,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.checkpointer = Objects.requireNonNull(checkpointer, "checkpointer 不能为空");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 创建用于直接传入状态的测试投影器。 */
    public ConversationRunProjector(ConversationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.checkpointer = null;
        this.auditSink = ConversationAuditSink.noop();
        this.clock = Clock.systemUTC();
    }

    /** 创建用于直接传入终态状态并捕获业务审计的测试投影器。 */
    public ConversationRunProjector(
            ConversationRepository repository,
            ConversationAuditSink auditSink,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.checkpointer = null;
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 从权威 Checkpoint 补偿尚未收到终态事件的运行轮次。
     *
     * @param turn 待读取的会话轮次
     */
    public void reconcile(ConversationTurnRecord turn) {
        Objects.requireNonNull(turn, "turn 不能为空");
        if (checkpointer == null
                || turn.status() != ConversationTurnStatus.RUNNING
                || turn.runId() == null) {
            return;
        }
        checkpointer.loadLatest(turn.runId())
                .ifPresent(checkpoint -> {
                    TraceEvent terminal = terminalEvent(checkpoint);
                    if (terminal != null) {
                        project(terminal, checkpoint.state());
                    }
                });
    }

    /** 接收 Run 终态事件并从权威 Checkpoint 读取最终状态。 */
    @Override
    public void publish(TraceEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        if (!isTerminal(event)) {
            return;
        }
        AgentState state = checkpointer == null
                ? AgentState.empty()
                : checkpointer.loadLatest(event.runId())
                        .map(RunCheckpoint::state)
                        .orElseThrow(() -> new IllegalStateException("Run Checkpoint 不存在: " + event.runId()));
        project(event, state);
    }

    /** 允许测试或组合发布器显式传入终态 AgentState。 */
    public void publish(TraceEvent event, AgentState terminalState) {
        Objects.requireNonNull(terminalState, "terminalState 不能为空");
        if (isTerminal(event)) {
            project(event, terminalState);
        }
    }

    private void project(TraceEvent event, AgentState state) {
        if (projected.putIfAbsent(event.runId(), Boolean.TRUE) != null) {
            return;
        }
        try {
            Optional<ConversationTurnRecord> turn = repository.findTurnByRunId(event.runId());
            if (turn.isEmpty()) {
                projected.remove(event.runId());
                return;
            }
            if (event instanceof TraceEvent.Completed) {
                String assistant = resolveAssistant(state);
                ConversationTurnRecord completed = repository.markTurnCompleted(
                        turn.get().turnId(), assistant, clock.instant());
                audit(terminalEvent(
                        ConversationAuditEventType.CONVERSATION_TURN_COMPLETED,
                        completed, state, assistant, null));
            } else if (event instanceof TraceEvent.Failed failed) {
                ConversationTurnRecord failedTurn = repository.markTurnFailed(
                        turn.get().turnId(), failed.error(), clock.instant());
                audit(terminalEvent(
                        ConversationAuditEventType.CONVERSATION_TURN_FAILED,
                        failedTurn, state, null, failed.error()));
            } else if (event instanceof TraceEvent.Rejected rejected) {
                ConversationTurnRecord rejectedTurn = repository.markTurnFailed(
                        turn.get().turnId(), rejected.reason(), clock.instant());
                audit(terminalEvent(
                        ConversationAuditEventType.CONVERSATION_TURN_FAILED,
                        rejectedTurn, state, null, rejected.reason()));
            }
        } catch (RuntimeException exception) {
            projected.remove(event.runId());
            throw exception;
        }
    }

    private void audit(ConversationAuditEvent event) {
        try {
            auditSink.record(event);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Conversation audit write failed eventType={} conversationId={} turnId={} runId={}",
                    event.eventType(), event.conversationId(), event.turnId(), event.runId(), exception);
        }
    }

    private ConversationAuditEvent terminalEvent(
            ConversationAuditEventType eventType,
            ConversationTurnRecord turn,
            AgentState state,
            String assistantContent,
            String error) {
        Instant occurredAt = Objects.requireNonNullElse(turn.completedAt(), clock.instant());
        return new ConversationAuditEvent(
                eventType,
                occurredAt,
                text(state, "planner.userId"),
                uuid(state, "conversation.workspaceId"),
                turn.conversationId(),
                turn.turnId(),
                turn.runId(),
                turn.turnIndex(),
                turn.status().name(),
                turn.userContent(),
                assistantContent,
                error,
                Math.max(0L, java.time.Duration.between(
                        turn.createdAt(), occurredAt).toMillis()));
    }

    private String text(AgentState state, String key) {
        String value = state.variables().get(key);
        return value == null || value.isBlank() ? null : value;
    }

    private UUID uuid(AgentState state, String key) {
        String value = text(state, key);
        return value == null ? null : UUID.fromString(value);
    }

    private TraceEvent terminalEvent(RunCheckpoint checkpoint) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        return switch (checkpoint.status()) {
            case COMPLETED -> new TraceEvent.Completed(
                    eventId, checkpoint.runId(), checkpoint.version(), occurredAt);
            case FAILED -> new TraceEvent.Failed(
                    eventId, checkpoint.runId(), checkpoint.version(), occurredAt,
                    Objects.requireNonNull(checkpoint.error(), "FAILED Checkpoint 缺少 error"));
            case REJECTED -> new TraceEvent.Rejected(
                    eventId, checkpoint.runId(), checkpoint.version(), occurredAt,
                    Objects.requireNonNull(checkpoint.interruptRequest(),
                            "REJECTED Checkpoint 缺少 interruptRequest").nodeName(),
                    Objects.requireNonNull(checkpoint.approvalReason(),
                            "REJECTED Checkpoint 缺少 approvalReason"));
            case RUNNING, WAITING_APPROVAL -> null;
        };
    }

    private String resolveAssistant(AgentState state) {
        return firstText(state.variables(),
                "final_response", "reviewer.feedback", "reviewer.summary", "planner.response")
                .orElseThrow(() -> new IllegalStateException("完成 Run 缺少最终响应"));
    }

    private Optional<String> firstText(Map<String, String> variables, String... keys) {
        for (String key : keys) {
            String value = variables.get(key);
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private boolean isTerminal(TraceEvent event) {
        return event instanceof TraceEvent.Completed
                || event instanceof TraceEvent.Failed
                || event instanceof TraceEvent.Rejected;
    }
}
