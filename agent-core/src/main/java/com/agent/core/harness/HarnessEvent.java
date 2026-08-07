package com.agent.core.harness;

import com.agent.core.engine.AgentState;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Hook 接收的不可变执行事件。 */
public record HarnessEvent(
        UUID runId,
        String nodeName,
        HarnessEventType eventType,
        Instant occurredAt,
        AgentState state,
        Map<String, String> metadata) {

    /** 校验事件并冻结元数据。 */
    public HarnessEvent {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName 不能为空");
        }
        Objects.requireNonNull(eventType, "eventType 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata 不能为空"));
        metadata.forEach((key, value) -> {
            if (key.isBlank()) {
                throw new IllegalArgumentException("metadata 键不能为空");
            }
        });
    }
}
