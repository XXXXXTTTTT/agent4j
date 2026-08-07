package com.agent.rag.pipeline;

import java.util.Objects;

/** 单个 RAG 阶段的不可变审计证据。 */
public record RagStageEvidence(
        RagStage stage,
        RagStageStatus status,
        int inputCount,
        int outputCount,
        int estimatedTokens,
        String detail,
        String errorStack) {

    /** 校验计数以及状态与错误堆栈的一致性。 */
    public RagStageEvidence {
        Objects.requireNonNull(stage, "stage 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        if (inputCount < 0 || outputCount < 0 || estimatedTokens < 0) {
            throw new IllegalArgumentException("阶段计数和 token 不能为负数");
        }
        detail = Objects.requireNonNull(detail, "detail 不能为空");
        errorStack = Objects.requireNonNull(errorStack, "errorStack 不能为空");
        if (status == RagStageStatus.DEGRADED && errorStack.isBlank()) {
            throw new IllegalArgumentException("降级阶段必须包含 errorStack");
        }
        if (status != RagStageStatus.DEGRADED && !errorStack.isEmpty()) {
            throw new IllegalArgumentException("非降级阶段不能包含 errorStack");
        }
    }
}
