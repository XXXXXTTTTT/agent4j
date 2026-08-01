package com.agent.core.trace;

import java.util.Objects;

/** 发布 Run Trace 事件的端口。 */
@FunctionalInterface
public interface TraceEventPublisher {

    /** 发布一个不可变 Trace 事件。 */
    void publish(TraceEvent event);

    /**
     * 返回丢弃事件的发布器。
     *
     * @return 无副作用发布器
     */
    static TraceEventPublisher noop() {
        return event -> Objects.requireNonNull(event, "event 不能为空");
    }
}
