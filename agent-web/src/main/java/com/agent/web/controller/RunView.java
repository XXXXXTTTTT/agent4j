package com.agent.web.controller;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.ApprovalDecision;
import com.agent.core.engine.InterruptRequest;
import com.agent.core.engine.RunCheckpoint;
import com.agent.core.engine.RunStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Run 最新 Checkpoint 的 HTTP 只读视图。
 *
 * @param runId Run 标识
 * @param version Checkpoint 版本
 * @param graphId 精确图标识
 * @param status 生命周期状态
 * @param state Agent 状态
 * @param nextNode 下一个待执行节点
 * @param interruptRequest 中断请求
 * @param approvalDecision 审批决定
 * @param approvalReason 审批原因
 * @param error 完整错误栈
 * @param createdAt 快照创建时间
 */
public record RunView(
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

    /** 从权威 Checkpoint 创建视图。 */
    public static RunView from(RunCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint 不能为空");
        return new RunView(
                checkpoint.runId(),
                checkpoint.version(),
                checkpoint.graphId(),
                checkpoint.status(),
                checkpoint.state(),
                checkpoint.nextNode(),
                checkpoint.interruptRequest(),
                checkpoint.approvalDecision(),
                checkpoint.approvalReason(),
                checkpoint.error(),
                checkpoint.createdAt());
    }
}
