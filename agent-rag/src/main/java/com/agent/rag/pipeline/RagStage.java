package com.agent.rag.pipeline;

/** 可审计的 RAG 流水线阶段。 */
public enum RagStage {
    QUERY_REWRITE,
    HYDE,
    BASELINE_RETRIEVAL,
    FUSION,
    RERANK,
    TOKEN_BUDGET
}
