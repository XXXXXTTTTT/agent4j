package com.agent.core.trace;

/** 实时 Run 日志发布端口。 */
@FunctionalInterface
public interface RunLogPublisher {

    /** 发布一个不可变日志事件。 */
    void publish(RunLogEvent event);

    /** 返回丢弃全部事件的发布器。 */
    static RunLogPublisher noop() {
        return ignored -> { };
    }
}
