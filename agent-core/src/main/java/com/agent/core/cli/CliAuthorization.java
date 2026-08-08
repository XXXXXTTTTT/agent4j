package com.agent.core.cli;

import java.util.Objects;

/** CLI 命令授权结果。 */
public record CliAuthorization(
        CliAuthorizationDecision decision,
        String reason,
        CliCommandPlan plan) {

    /** 校验授权结果。 */
    public CliAuthorization {
        decision = Objects.requireNonNull(decision, "decision 不能为空");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        plan = Objects.requireNonNull(plan, "plan 不能为空");
    }
}
