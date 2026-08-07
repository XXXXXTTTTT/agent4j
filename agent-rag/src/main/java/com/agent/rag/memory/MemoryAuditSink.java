package com.agent.rag.memory;

import java.util.List;
import java.util.UUID;

/** 接收长期记忆访问更新失败的审计端口。 */
@FunctionalInterface
public interface MemoryAuditSink {

    /** 记录一次访问更新失败，不改变已经计算的召回结果。 */
    void recordAccessFailure(
            MemoryQuery query,
            List<UUID> memoryIds,
            RuntimeException failure);

    /** 返回忽略审计写入的默认实现。 */
    static MemoryAuditSink noop() {
        return (query, memoryIds, failure) -> {
        };
    }
}
