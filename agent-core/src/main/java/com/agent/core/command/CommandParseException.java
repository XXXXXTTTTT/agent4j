package com.agent.core.command;

/** Slash Command 输入语法错误。 */
public final class CommandParseException extends RuntimeException {

    /** 创建带中文原因的解析异常。 */
    public CommandParseException(String message) {
        super(message);
    }
}
