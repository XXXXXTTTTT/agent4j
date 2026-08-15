package com.agent.web.audit;

/** 工作区文件审计写入端口。 */
@FunctionalInterface
public interface WorkspaceFileAuditSink {
    void record(WorkspaceFileAuditEvent event);
    static WorkspaceFileAuditSink noop() { return event -> { }; }
}
