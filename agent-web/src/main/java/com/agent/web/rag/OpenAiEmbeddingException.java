package com.agent.web.rag;

/** OpenAI 兼容 Embedding 协议或 HTTP 调用失败。 */
public final class OpenAiEmbeddingException extends RuntimeException {

    /** 创建带原始故障 cause 的 Embedding 异常。 */
    public OpenAiEmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
