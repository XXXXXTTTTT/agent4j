package com.agent.core.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 不包含敏感原文的安全违规事件。 */
public record SecurityViolation(
        UUID violationId,
        UUID runId,
        String userId,
        String nodeName,
        Optional<String> toolName,
        SecurityViolationType type,
        SecuritySeverity severity,
        String ruleId,
        String summary,
        Instant occurredAt) {

    public SecurityViolation {
        Objects.requireNonNull(violationId, "violationId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        requireText(userId, "userId");
        requireText(nodeName, "nodeName");
        toolName = Objects.requireNonNull(toolName, "toolName 不能为空").map(SecurityViolation::requireToolName);
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(severity, "severity 不能为空");
        requireText(ruleId, "ruleId");
        requireText(summary, "summary");
        if (summary.indexOf('\n') >= 0 || summary.indexOf('\r') >= 0
                || summary.contains("Bearer ") || summary.contains("sk-")) {
            throw new IllegalArgumentException("summary 包含未脱敏内容");
        }
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
    }

    private static String requireToolName(String value) {
        requireText(value, "toolName");
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
