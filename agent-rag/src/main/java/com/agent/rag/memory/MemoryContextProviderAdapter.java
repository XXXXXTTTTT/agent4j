package com.agent.rag.memory;

import com.agent.core.memory.MemoryContext;
import com.agent.core.memory.MemoryContextProvider;
import com.agent.core.memory.MemoryContextRequest;

import java.util.EnumSet;
import java.util.Objects;

/** 将长期记忆命中格式化为核心 Planner 可注入的上下文。 */
public final class MemoryContextProviderAdapter implements MemoryContextProvider {

    private final MemoryManager memoryManager;

    /** 创建记忆上下文适配器。 */
    public MemoryContextProviderAdapter(MemoryManager memoryManager) {
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager 不能为空");
    }

    /** 按 manager 的稳定排序格式化记忆，不在适配层重新排序。 */
    @Override
    public MemoryContext recall(MemoryContextRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        var hits = memoryManager.recall(new MemoryQuery(
                request.repositoryId(),
                request.userId(),
                request.query(),
                EnumSet.allOf(MemoryType.class),
                request.limit()));
        if (hits.isEmpty()) {
            return new MemoryContext("", 0);
        }
        String prompt = hits.stream()
                .map(hit -> "[" + hit.entry().type().name() + "] "
                        + hit.entry().title() + "\n" + hit.entry().content())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return new MemoryContext(prompt, hits.size());
    }
}
