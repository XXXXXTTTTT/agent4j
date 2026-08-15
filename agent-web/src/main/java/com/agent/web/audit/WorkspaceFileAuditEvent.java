package com.agent.web.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 不包含文件正文的工作区文件审计事件。 */
public record WorkspaceFileAuditEvent(WorkspaceFileAuditEventType eventType, Instant occurredAt,
        String userId, UUID workspaceId, String path, long bytes, String sha256, String result) {
    public WorkspaceFileAuditEvent {
        Objects.requireNonNull(eventType, "eventType 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(workspaceId, "workspaceId 不能为空");
        Objects.requireNonNull(path, "path 不能为空");
        if (bytes < 0) throw new IllegalArgumentException("bytes 不能为负数");
        Objects.requireNonNull(result, "result 不能为空");
    }
}
