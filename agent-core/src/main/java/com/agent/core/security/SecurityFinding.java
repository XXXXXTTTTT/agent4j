package com.agent.core.security;

import java.util.Objects;

/** 不包含输入正文的安全规则发现。 */
public record SecurityFinding(
        String ruleId,
        SecuritySeverity severity,
        SecurityDecision decision,
        String summary) {

    public SecurityFinding {
        requireText(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity 不能为空");
        Objects.requireNonNull(decision, "decision 不能为空");
        requireSummary(summary);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static void requireSummary(String value) {
        requireText(value, "summary");
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("summary 不能包含换行");
        }
    }
}
