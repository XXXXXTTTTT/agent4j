package com.agent.rag.store;

/** RAG 数据库操作失败。 */
public final class RagStoreException extends RuntimeException {

    public RagStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
