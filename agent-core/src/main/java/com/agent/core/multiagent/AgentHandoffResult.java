package com.agent.core.multiagent;

import com.agent.core.engine.AgentState;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** 一次成功 Handoff 的子状态、合并状态和执行证据。 */
public record AgentHandoffResult(
        UUID taskId,
        UUID parentRunId,
        UUID childRunId,
        String fromAgent,
        String toAgent,
        AgentState childState,
        AgentState mergedParentState,
        HandoffExecutionContext childContext,
        Duration elapsed) {

    public AgentHandoffResult {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(parentRunId, "parentRunId 不能为空");
        Objects.requireNonNull(childRunId, "childRunId 不能为空");
        if (parentRunId.equals(childRunId)) {
            throw new IllegalArgumentException("childRunId 必须与 parentRunId 不同");
        }
        requireText(fromAgent, "fromAgent");
        requireText(toAgent, "toAgent");
        Objects.requireNonNull(childState, "childState 不能为空");
        Objects.requireNonNull(mergedParentState, "mergedParentState 不能为空");
        Objects.requireNonNull(childContext, "childContext 不能为空");
        Objects.requireNonNull(elapsed, "elapsed 不能为空");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed 不能为负数");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
