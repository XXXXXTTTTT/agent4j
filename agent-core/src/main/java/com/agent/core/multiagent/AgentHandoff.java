package com.agent.core.multiagent;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 一次结构化 Agent 任务移交。 */
public record AgentHandoff(
        UUID taskId,
        String fromAgent,
        String toAgent,
        String content,
        HandoffContextMode contextMode,
        Set<String> requestedOutputKeys,
        Duration timeout) {

    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    public AgentHandoff {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        requireText(fromAgent, "fromAgent");
        requireText(toAgent, "toAgent");
        requireText(content, "content");
        Objects.requireNonNull(contextMode, "contextMode 不能为空");
        Objects.requireNonNull(requestedOutputKeys, "requestedOutputKeys 不能为空");
        if (requestedOutputKeys.isEmpty()) {
            throw new IllegalArgumentException("requestedOutputKeys 不能为空集合");
        }
        LinkedHashSet<String> checkedOutputs = new LinkedHashSet<>();
        for (String key : requestedOutputKeys) {
            requireText(key, "requestedOutputKeys");
            checkedOutputs.add(key);
        }
        requestedOutputKeys = Set.copyOf(checkedOutputs);
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout 必须大于 0 且不超过 10 分钟");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
