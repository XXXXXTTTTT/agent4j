package com.agent.core.harness;

/** 非关键 Hook 失败的审计端口。 */
@FunctionalInterface
public interface HarnessAuditSink {

    /** 记录完整 Hook 失败。 */
    void record(HarnessHookException failure);

    /** 返回不执行外部副作用的默认实现。 */
    static HarnessAuditSink noop() {
        return failure -> { };
    }
}
