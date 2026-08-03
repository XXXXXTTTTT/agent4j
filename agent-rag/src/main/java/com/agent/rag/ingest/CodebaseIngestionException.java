package com.agent.rag.ingest;

/** 代码库读取、切片或 embedding 失败。 */
public final class CodebaseIngestionException extends RuntimeException {

    public CodebaseIngestionException(String message) {
        super(message);
    }

    public CodebaseIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
