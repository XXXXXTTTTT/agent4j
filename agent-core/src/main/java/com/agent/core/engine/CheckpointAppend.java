package com.agent.core.engine;

import java.util.Objects;
import java.util.UUID;

/**
 * 追加下一版本 Checkpoint 的输入。
 *
 * @param runId               Run 标识
 * @param expectedVersion     预期最新版本
 * @param status              新状态
 * @param state               Agent 状态
 * @param nextNode            下一个待执行节点
 * @param interruptRequest    中断请求
 * @param approvalDecision    审批决定
 * @param approvalReason      审批原因
 * @param error               完整错误栈
 */
public record CheckpointAppend(
        UUID runId,
        long expectedVersion,
        RunStatus status,
        AgentState state,
        String nextNode,
        InterruptRequest interruptRequest,
        ApprovalDecision approvalDecision,
        String approvalReason,
        String error) {

    /** 校验追加输入。 */
    public CheckpointAppend {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion 不能小于 0");
        }
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        RunCheckpoint.validateState(
                status,
                nextNode,
                interruptRequest,
                approvalDecision,
                approvalReason,
                error);
    }
}
