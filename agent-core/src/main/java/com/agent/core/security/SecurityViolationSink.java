package com.agent.core.security;

/** 安全违规持久化端口。 */
@FunctionalInterface
public interface SecurityViolationSink {

    /** 记录一个已经脱敏的安全违规。 */
    void record(SecurityViolation violation);

    /** 返回丢弃事件的默认 Sink。 */
    static SecurityViolationSink noop() {
        return violation -> { };
    }
}
