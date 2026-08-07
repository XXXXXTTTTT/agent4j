package com.agent.rag.pipeline;

/** RAG 各阶段的不可变执行限制。 */
public record RagRetrievalPolicy(
        int rewriteLimit,
        boolean hydeEnabled,
        int retrievalLimit,
        int rerankLimit,
        int maxContextTokens) {

    /** 校验查询数量、召回数量与上下文预算。 */
    public RagRetrievalPolicy {
        if (rewriteLimit < 1 || rewriteLimit > 3) {
            throw new IllegalArgumentException("rewriteLimit 必须在 1 到 3 之间");
        }
        if (retrievalLimit < 1 || retrievalLimit > 100) {
            throw new IllegalArgumentException("retrievalLimit 必须在 1 到 100 之间");
        }
        if (rerankLimit < 1) {
            throw new IllegalArgumentException("rerankLimit 必须大于 0");
        }
        if (rerankLimit > retrievalLimit) {
            throw new IllegalArgumentException("rerankLimit 不能超过 retrievalLimit");
        }
        if (maxContextTokens < 1) {
            throw new IllegalArgumentException("maxContextTokens 必须大于 0");
        }
    }
}
