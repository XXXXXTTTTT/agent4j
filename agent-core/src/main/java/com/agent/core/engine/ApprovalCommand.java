package com.agent.core.engine;

import java.util.Objects;

/**
 * 人工审批命令。
 *
 * @param decision        审批决定
 * @param expectedVersion 客户端看到的最新 Checkpoint 版本
 * @param reason          审批原因
 */
public record ApprovalCommand(
        ApprovalDecision decision,
        long expectedVersion,
        String reason) {

    /** 校验审批命令。 */
    public ApprovalCommand {
        Objects.requireNonNull(decision, "decision 不能为空");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion 不能小于 0");
        }
        requireText(reason, "reason");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
