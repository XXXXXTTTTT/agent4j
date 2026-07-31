package com.agent.sandbox.ast;

/** AST 解析或补丁应用失败。 */
public final class AstServiceException extends RuntimeException {

    /** 使用错误消息创建异常。 */
    public AstServiceException(String message) {
        super(message);
    }

    /** 使用错误消息和原始异常创建异常。 */
    public AstServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
