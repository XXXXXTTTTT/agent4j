package com.agent.core.engine;

import java.util.Map;
import java.util.Objects;

/**
 * 人工审批命令。
 *
 * @param decision        审批决定
 * @param expectedVersion 客户端看到的最新 Checkpoint 版本
 * @param reason          审批原因
 * @param variableUpdates 批准时请求更新的精确状态变量
 */
public record ApprovalCommand(
        ApprovalDecision decision,
        long expectedVersion,
        String reason,
        Map<String, String> variableUpdates) {

    /** 创建不修改状态变量的审批命令。 */
    public ApprovalCommand(
            ApprovalDecision decision,
            long expectedVersion,
            String reason) {
        this(decision, expectedVersion, reason, Map.of());
    }

    /** 校验审批命令。 */
    public ApprovalCommand {
        Objects.requireNonNull(decision, "decision 不能为空");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion 不能小于 0");
        }
        requireText(reason, "reason");
        variableUpdates = Map.copyOf(Objects.requireNonNull(
                variableUpdates, "variableUpdates 不能为空"));
        variableUpdates.keySet().forEach(key -> requireText(key, "variableUpdates key"));
        if (decision == ApprovalDecision.REJECT && !variableUpdates.isEmpty()) {
            throw new IllegalArgumentException("REJECT 不允许 variableUpdates");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
