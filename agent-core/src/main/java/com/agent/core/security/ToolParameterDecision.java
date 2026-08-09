package com.agent.core.security;

import java.util.Objects;

/** 工具参数安全策略结果。 */
public record ToolParameterDecision(
        SecurityDecision decision,
        String ruleId,
        String summary) {

    public ToolParameterDecision {
        Objects.requireNonNull(decision, "decision 不能为空");
        if (ruleId == null) {
            throw new IllegalArgumentException("ruleId 不能为空");
        }
        if (summary == null) {
            throw new IllegalArgumentException("summary 不能为空");
        }
        if (ruleId.indexOf('\n') >= 0 || ruleId.indexOf('\r') >= 0
                || summary.indexOf('\n') >= 0 || summary.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("安全规则字段不能包含换行");
        }
        if (decision == SecurityDecision.BLOCK && (ruleId.isBlank() || summary.isBlank())) {
            throw new IllegalArgumentException("BLOCK 必须包含规则和摘要");
        }
        if (decision == SecurityDecision.ALLOW && (!ruleId.isEmpty() || !summary.isEmpty())) {
            throw new IllegalArgumentException("ALLOW 的规则和摘要必须为空");
        }
    }
}
