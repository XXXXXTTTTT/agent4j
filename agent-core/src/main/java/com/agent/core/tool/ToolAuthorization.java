package com.agent.core.tool;

import java.util.Objects;

/** 带审计理由的工具授权结果。 */
public record ToolAuthorization(
        ToolAuthorizationDecision decision,
        String reason) {

    /** 校验授权决策与理由。 */
    public ToolAuthorization {
        Objects.requireNonNull(decision, "decision 不能为空");
        if (reason == null) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        if (decision != ToolAuthorizationDecision.ALLOWED && reason.isBlank()) {
            throw new IllegalArgumentException("非 ALLOWED 决策必须提供 reason");
        }
    }
}
