package com.agent.core.tool;

import java.util.Objects;

/** 工具审计事件输出端口。 */
@FunctionalInterface
public interface ToolAuditSink {

    /** 记录一次工具调用事件。 */
    void record(ToolAuditEvent event);

    /** 返回不产生外部副作用的默认审计端口。 */
    static ToolAuditSink noop() {
        return event -> Objects.requireNonNull(event, "event 不能为空");
    }
}
