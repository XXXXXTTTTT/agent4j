package com.agent.web.audit;

/** 会话业务审计输出端口。 */
@FunctionalInterface
public interface ConversationAuditSink {

    /** 持久记录一条会话审计事件。 */
    void record(ConversationAuditEvent event);

    /** 提供不产生外部副作用的默认实现。 */
    static ConversationAuditSink noop() {
        return event -> { };
    }
}
