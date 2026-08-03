package com.agent.rag.memory;

/** 长期记忆数据库失败，保留原始 cause。 */
public final class MemoryStoreException extends RuntimeException {

    /** 创建带原始原因的存储异常。 */
    public MemoryStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
