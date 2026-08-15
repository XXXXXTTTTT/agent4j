package com.agent.web.audit;

/** 工作区项目与文件操作审计类型。 */
public enum WorkspaceFileAuditEventType {
    PROJECT_CREATED, FILE_LISTED, FILE_READ, FILE_WRITTEN, FILE_CONFLICT, FILE_REJECTED
}
