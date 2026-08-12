package com.agent.web.capability;

/** 写入能力管理审计事件。 */
@FunctionalInterface
public interface CapabilityManagementAuditSink {
    void record(CapabilityManagementAuditEvent event);

    static CapabilityManagementAuditSink noop() {
        return event -> { };
    }
}
