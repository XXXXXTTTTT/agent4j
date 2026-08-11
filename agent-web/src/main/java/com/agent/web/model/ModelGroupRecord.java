package com.agent.web.model;

import com.agent.core.llm.TaskType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 用户自定义模型组及其端点顺序。 */
public record ModelGroupRecord(
        UUID groupId,
        String ownerUserId,
        String displayName,
        TaskType taskType,
        List<UUID> endpointIds,
        Instant createdAt,
        Instant updatedAt) {

    public ModelGroupRecord {
        endpointIds = List.copyOf(endpointIds);
    }
}
