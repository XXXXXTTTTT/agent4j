package com.agent.core.llm;

/**
 * LLM 请求、协议解析或流式消费失败。
 */
public class LlmClientException extends RuntimeException {

    /**
     * 创建带原因的客户端异常。
     *
     * @param message 错误说明
     * @param cause   原始异常
     */
    public LlmClientException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 创建客户端异常。
     *
     * @param message 错误说明
     */
    public LlmClientException(String message) {
        super(message);
    }
}
