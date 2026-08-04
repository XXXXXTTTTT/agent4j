package com.agent.rag.memory;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Checkpointer;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 将失败 Run 的固定证据归因并写入 BAD_CASE 长期记忆。 */
public final class RunBadCaseAttributor implements TraceEventPublisher {

    private static final List<String> EVIDENCE_KEYS = List.of(
            "coder.updatedFiles", "coder.error", "ops.exitCode", "ops.stdout",
            "ops.stderr", "ops.timedOut", "ops.error", "reviewer.approved",
            "reviewer.summary", "reviewer.feedback", "reviewer.error");
    private static final int FIELD_LIMIT = 4_000;
    private static final int SOURCE_LIMIT = 20_000;

    private final Checkpointer checkpointer;
    private final MemoryManager memoryManager;

    /** 创建终态归因器。 */
    public RunBadCaseAttributor(Checkpointer checkpointer, MemoryManager memoryManager) {
        this.checkpointer = Objects.requireNonNull(checkpointer, "checkpointer 不能为空");
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager 不能为空");
    }

    @Override
    public void publish(TraceEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        if (!(event instanceof TraceEvent.Failed)
                && !(event instanceof TraceEvent.Rejected)
                && !(event instanceof TraceEvent.Completed)) {
            return;
        }
        RunCheckpoint checkpoint = checkpointer.loadLatest(event.runId())
                .orElseThrow(() -> new IllegalStateException("找不到 Run Checkpoint: " + event.runId()));
        if (checkpoint.version() != event.checkpointVersion()) {
            throw new IllegalStateException("Trace 与 Checkpoint 版本不一致");
        }
        AgentState state = checkpoint.state();
        if (state.variables().containsKey("ops.exitCode")) {
            parseExitCode(state.variables().get("ops.exitCode"));
        }
        if (event instanceof TraceEvent.Completed && !isFailed(state.variables())) {
            return;
        }
        String repositoryId = requireScope(state.variables(), "planner.repositoryId");
        String userId = requireScope(state.variables(), "planner.userId");
        String task = requireScope(state.variables(), "planner.task");
        String source = buildSource(task, state.variables(), event);
        memoryManager.captureBadCases(new MemoryCapture(repositoryId, userId, source));
    }

    private boolean isFailed(Map<String, String> variables) {
        if ("false".equals(variables.get("reviewer.approved"))) {
            return true;
        }
        if ("true".equals(variables.get("ops.timedOut"))) {
            return true;
        }
        String exitCode = variables.get("ops.exitCode");
        if (exitCode != null && parseExitCode(exitCode) != 0) {
            return true;
        }
        return List.of("coder.error", "ops.error", "reviewer.error")
                .stream().anyMatch(key -> present(variables.get(key)));
    }

    private String buildSource(String task, Map<String, String> variables, TraceEvent event) {
        StringBuilder source = new StringBuilder("仅返回 BAD_CASE 类型。\n")
                .append("planner.task=").append(limit(task)).append('\n')
                .append("terminal.event=").append(event.type()).append('\n');
        for (String key : EVIDENCE_KEYS) {
            String value = variables.get(key);
            if (value != null) {
                source.append(key).append('=').append(limit(value)).append('\n');
            }
        }
        return source.substring(0, Math.min(source.length(), SOURCE_LIMIT));
    }

    private int parseExitCode(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("ops.exitCode 必须是十进制整数", exception);
        }
    }

    private String requireScope(Map<String, String> variables, String key) {
        String value = variables.get(key);
        if (!present(value)) {
            throw new IllegalArgumentException("缺少状态变量: " + key);
        }
        return value;
    }

    private String limit(String value) {
        return value.length() <= FIELD_LIMIT ? value : value.substring(0, FIELD_LIMIT);
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
