package com.agent.rag.memory;

/** 模型记忆提取失败，保留原始 cause。 */
public final class MemoryExtractionException extends RuntimeException {

    /** 创建带原始原因的提取异常。 */
    public MemoryExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
