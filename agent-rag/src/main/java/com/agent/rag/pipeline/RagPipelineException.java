package com.agent.rag.pipeline;

/** RAG 基础阶段无法继续执行。 */
public final class RagPipelineException extends RuntimeException {

    /** 保留基础失败的原始 cause。 */
    public RagPipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
