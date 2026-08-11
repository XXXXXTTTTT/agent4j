package com.agent.web.model;

import com.agent.core.llm.InferenceCapability;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 用户级模型端点及其调度参数。 */
public record ModelEndpointRecord(
        UUID endpointId,
        UUID providerId,
        String displayName,
        String modelId,
        Set<InferenceCapability> capabilities,
        int priority,
        int weight,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public ModelEndpointRecord {
        capabilities = Set.copyOf(capabilities);
    }
}
