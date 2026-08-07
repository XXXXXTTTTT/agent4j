package com.agent.rag.pipeline;

/** RAG 阶段的确定性执行状态。 */
public enum RagStageStatus {
    APPLIED,
    SKIPPED,
    DEGRADED
}
