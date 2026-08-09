package com.agent.core.security;

import java.util.List;
import java.util.Objects;

/** Prompt 安全检查的不可变结果。 */
public record PromptSecurityAssessment(
        SecurityDecision decision,
        List<SecurityFinding> findings) {

    public PromptSecurityAssessment {
        Objects.requireNonNull(decision, "decision 不能为空");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings 不能为空"));
        if (decision == SecurityDecision.ALLOW && !findings.isEmpty()) {
            throw new IllegalArgumentException("ALLOW 不得包含安全发现");
        }
        if (decision != SecurityDecision.ALLOW && findings.isEmpty()) {
            throw new IllegalArgumentException("非 ALLOW 决定必须包含安全发现");
        }
    }
}
