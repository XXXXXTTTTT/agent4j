package com.agent.core.command;

import java.util.Objects;

/** 命令授权判断结果。 */
public record CommandAuthorizationDecision(boolean allowed, String reason) {

    /** 校验原因文本。 */
    public CommandAuthorizationDecision {
        reason = Objects.requireNonNullElse(reason, "");
    }

    /** 创建允许结果。 */
    public static CommandAuthorizationDecision allow() {
        return new CommandAuthorizationDecision(true, "");
    }

    /** 创建拒绝结果。 */
    public static CommandAuthorizationDecision deny(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("拒绝原因不能为空");
        }
        return new CommandAuthorizationDecision(false, reason);
    }
}
