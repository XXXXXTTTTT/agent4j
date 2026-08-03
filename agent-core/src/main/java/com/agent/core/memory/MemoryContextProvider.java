package com.agent.core.memory;

/** 由规划节点调用的长期记忆召回端口。 */
@FunctionalInterface
public interface MemoryContextProvider {

    /**
     * 召回并格式化指定范围的长期记忆。
     *
     * @param request 精确范围和查询请求
     * @return 不可变记忆上下文
     */
    MemoryContext recall(MemoryContextRequest request);
}
