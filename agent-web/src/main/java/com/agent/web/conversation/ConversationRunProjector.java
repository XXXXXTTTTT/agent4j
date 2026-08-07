package com.agent.web.conversation;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 将 Run 终态幂等投影为会话轮次终态。 */
public final class ConversationRunProjector implements TraceEventPublisher {

    private final ConversationRepository repository;
    private final Checkpointer checkpointer;
    private final Clock clock;
    private final ConcurrentMap<UUID, Boolean> projected = new ConcurrentHashMap<>();

    /** 创建带 Checkpointer 的生产投影器。 */
    public ConversationRunProjector(
            ConversationRepository repository,
            Checkpointer checkpointer,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.checkpointer = Objects.requireNonNull(checkpointer, "checkpointer 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 创建用于直接传入状态的测试投影器。 */
    public ConversationRunProjector(ConversationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.checkpointer = null;
        this.clock = Clock.systemUTC();
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
        if (projected.containsKey(event.runId())) {
            return;
        }
        Optional<ConversationTurnRecord> turn = repository.findTurnByRunId(event.runId());
        if (turn.isEmpty()) {
            return;
        }
        try {
            if (event instanceof TraceEvent.Completed) {
                repository.markTurnCompleted(
                        turn.get().turnId(), resolveAssistant(state), clock.instant());
            } else if (event instanceof TraceEvent.Failed failed) {
                repository.markTurnFailed(turn.get().turnId(), failed.error(), clock.instant());
            } else if (event instanceof TraceEvent.Rejected rejected) {
                repository.markTurnFailed(turn.get().turnId(), rejected.reason(), clock.instant());
            }
            projected.put(event.runId(), Boolean.TRUE);
        } catch (RuntimeException exception) {
            projected.remove(event.runId());
            throw exception;
        }
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
