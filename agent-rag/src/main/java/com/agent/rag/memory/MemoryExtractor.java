package com.agent.rag.memory;

import java.util.List;

/** 从一次运行观察中提取长期记忆草稿的端口。 */
@FunctionalInterface
public interface MemoryExtractor {

    /**
     * 提取待持久化记忆。
     *
     * @param capture 原始观察
     * @return 有序、不可变的草稿列表
     */
    List<MemoryDraft> extract(MemoryCapture capture);
}
