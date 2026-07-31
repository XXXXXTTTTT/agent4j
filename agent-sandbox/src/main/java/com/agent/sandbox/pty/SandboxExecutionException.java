package com.agent.sandbox.pty;

/** 沙箱命令执行失败。 */
public final class SandboxExecutionException extends RuntimeException {

    /** 使用错误消息创建异常。 */
    public SandboxExecutionException(String message) {
        super(message);
    }

    /** 使用错误消息和原始异常创建异常。 */
    public SandboxExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
