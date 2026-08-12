package com.agent.web.capability;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 能力目录与安装操作的审计事件，不承载密钥值。 */
public record CapabilityManagementAuditEvent(
        String eventType,
        String actorUserId,
        UUID workspaceId,
        UUID installationId,
        UUID skillId,
        UUID runId,
        String sourceCommitSha,
        String result,
        Instant occurredAt,
        UUID operationId,
        String fromStatus,
        String toStatus,
        String detailSha256) {
    public CapabilityManagementAuditEvent(
            String eventType, String actorUserId, UUID workspaceId, UUID installationId,
            UUID skillId, UUID runId, String sourceCommitSha, String result, Instant occurredAt) {
        this(eventType, actorUserId, workspaceId, installationId, skillId, runId,
                sourceCommitSha, result, occurredAt, null, null, null, null);
    }

    public CapabilityManagementAuditEvent {
        eventType = required(eventType, "eventType");
        actorUserId = required(actorUserId, "actorUserId");
        sourceCommitSha = Objects.requireNonNullElse(sourceCommitSha, "");
        result = required(result, "result");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        fromStatus = Objects.requireNonNullElse(fromStatus, "");
        toStatus = Objects.requireNonNullElse(toStatus, "");
        detailSha256 = Objects.requireNonNullElse(detailSha256, "");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
