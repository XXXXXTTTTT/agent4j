package com.agent.core.memory;

import java.util.Objects;

/** 已格式化、可注入规划 Prompt 的长期记忆上下文。 */
public record MemoryContext(String prompt, int entryCount) {

    /** 校验格式化文本和条目数量。 */
    public MemoryContext {
        Objects.requireNonNull(prompt, "prompt 不能为空");
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount 不能为负数");
        }
    }
}
