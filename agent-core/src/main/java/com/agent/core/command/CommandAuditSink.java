package com.agent.core.command;

/** 命令审计事件接收端口。 */
@FunctionalInterface
public interface CommandAuditSink {

    /** 接收一条命令生命周期事件。 */
    void record(CommandAuditEvent event);
}
