package com.agent.sandbox.pty;

import java.util.Objects;

/**
 * 一段实时终端日志。
 *
 * @param stream 日志流
 * @param text   日志文本
 */
public record TerminalLog(Stream stream, String text) {

    /** 创建并校验日志。 */
    public TerminalLog {
        stream = Objects.requireNonNull(stream, "stream 不能为空");
        text = Objects.requireNonNull(text, "text 不能为空");
    }
}
