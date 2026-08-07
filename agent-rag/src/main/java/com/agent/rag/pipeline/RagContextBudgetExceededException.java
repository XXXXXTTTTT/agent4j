package com.agent.rag.pipeline;

/** 最高优先级子块本身超过 RAG 上下文预算。 */
public final class RagContextBudgetExceededException extends RuntimeException {

    private final int estimatedTokens;
    private final int limit;

    /** 保存观测 token 与配置上限。 */
    public RagContextBudgetExceededException(int estimatedTokens, int limit) {
        super("RAG 首条证据超过 token 预算: " + estimatedTokens + " > " + limit);
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        if (estimatedTokens <= limit) {
            throw new IllegalArgumentException("estimatedTokens 必须大于 limit");
        }
        this.estimatedTokens = estimatedTokens;
        this.limit = limit;
    }

    /** 返回首条证据的估算 token。 */
    public int estimatedTokens() {
        return estimatedTokens;
    }

    /** 返回配置 token 上限。 */
    public int limit() {
        return limit;
    }
}
