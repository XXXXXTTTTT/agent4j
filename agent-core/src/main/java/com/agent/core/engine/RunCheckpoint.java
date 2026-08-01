package com.agent.core.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Run 的不可变版本快照。
 *
 * @param runId               Run 标识
 * @param version             版本号
 * @param graphId             图标识
 * @param status              生命周期状态
 * @param state               Agent 状态
 * @param nextNode            下一个待执行节点
 * @param interruptRequest    中断请求
 * @param approvalDecision    审批决定
 * @param approvalReason      审批原因
 * @param error               完整错误栈
 * @param createdAt           快照创建时间
 */
public record RunCheckpoint(
        UUID runId,
        long version,
        String graphId,
        RunStatus status,
        AgentState state,
        String nextNode,
        InterruptRequest interruptRequest,
        ApprovalDecision approvalDecision,
        String approvalReason,
        String error,
        Instant createdAt) {

    /** 校验版本快照。 */
    public RunCheckpoint {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (version < 0) {
            throw new IllegalArgumentException("version 不能小于 0");
        }
        requireText(graphId, "graphId");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        validateState(status, nextNode, interruptRequest, approvalDecision, approvalReason, error);
    }

    static void validateState(
            RunStatus status,
            String nextNode,
            InterruptRequest interruptRequest,
            ApprovalDecision approvalDecision,
            String approvalReason,
            String error) {
        validateOptionalText(nextNode, "nextNode");
        validateOptionalText(approvalReason, "approvalReason");
        validateOptionalText(error, "error");

        switch (status) {
            case RUNNING -> validateRunning(
                    nextNode, interruptRequest, approvalDecision, approvalReason, error);
            case WAITING_APPROVAL -> validateWaiting(
                    nextNode, interruptRequest, approvalDecision, approvalReason, error);
            case COMPLETED -> validateCompleted(
                    nextNode, interruptRequest, approvalDecision, approvalReason, error);
            case REJECTED -> validateRejected(
                    nextNode, interruptRequest, approvalDecision, approvalReason, error);
            case FAILED -> validateFailed(
                    nextNode, interruptRequest, approvalDecision, approvalReason, error);
        }
    }

    private static void validateRunning(
            String nextNode,
            InterruptRequest interruptRequest,
            ApprovalDecision approvalDecision,
            String approvalReason,
            String error) {
        requireText(nextNode, "RUNNING.nextNode");
        requireNull(interruptRequest, "RUNNING.interruptRequest");
        requireNull(error, "RUNNING.error");
        if (approvalDecision == null) {
            requireNull(approvalReason, "RUNNING.approvalReason");
            return;
        }
        if (approvalDecision != ApprovalDecision.APPROVE) {
            throw new IllegalArgumentException("RUNNING 只允许 APPROVE 审批决定");
        }
        requireText(approvalReason, "RUNNING.approvalReason");
    }

    private static void validateWaiting(
            String nextNode,
            InterruptRequest interruptRequest,
            ApprovalDecision approvalDecision,
            String approvalReason,
            String error) {
        requireText(nextNode, "WAITING_APPROVAL.nextNode");
        requirePresent(interruptRequest, "WAITING_APPROVAL.interruptRequest");
        if (!nextNode.equals(interruptRequest.nodeName())) {
            throw new IllegalArgumentException("WAITING_APPROVAL 节点名称不一致");
        }
        requireNull(approvalDecision, "WAITING_APPROVAL.approvalDecision");
        requireNull(approvalReason, "WAITING_APPROVAL.approvalReason");
        requireNull(error, "WAITING_APPROVAL.error");
    }

    private static void validateCompleted(
            String nextNode,
            InterruptRequest interruptRequest,
            ApprovalDecision approvalDecision,
            String approvalReason,
            String error) {
        requireNull(nextNode, "COMPLETED.nextNode");
        requireNull(interruptRequest, "COMPLETED.interruptRequest");
        requireNull(approvalDecision, "COMPLETED.approvalDecision");
        requireNull(approvalReason, "COMPLETED.approvalReason");
        requireNull(error, "COMPLETED.error");
    }

    private static void validateRejected(
            String nextNode,
            InterruptRequest interruptRequest,
            ApprovalDecision approvalDecision,
            String approvalReason,
            String error) {
        requireNull(nextNode, "REJECTED.nextNode");
        requirePresent(interruptRequest, "REJECTED.interruptRequest");
        if (approvalDecision != ApprovalDecision.REJECT) {
            throw new IllegalArgumentException("REJECTED 必须携带 REJECT 审批决定");
        }
        requireText(approvalReason, "REJECTED.approvalReason");
        requireNull(error, "REJECTED.error");
    }

    private static void validateFailed(
            String nextNode,
            InterruptRequest interruptRequest,
            ApprovalDecision approvalDecision,
            String approvalReason,
            String error) {
        requireNull(nextNode, "FAILED.nextNode");
        requireNull(interruptRequest, "FAILED.interruptRequest");
        requireNull(approvalDecision, "FAILED.approvalDecision");
        requireNull(approvalReason, "FAILED.approvalReason");
        requireText(error, "FAILED.error");
    }

    private static void validateOptionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白字符串");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static void requireNull(Object value, String name) {
        if (value != null) {
            throw new IllegalArgumentException(name + " 必须为 null");
        }
    }

    private static void requirePresent(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
