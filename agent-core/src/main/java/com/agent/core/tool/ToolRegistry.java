package com.agent.core.tool;

import java.util.List;
import java.util.Optional;

/** 统一工具注册、治理、执行与审计端口。 */
public interface ToolRegistry extends AutoCloseable {

    /** 注册一个工具定义。 */
    void register(ToolDefinition definition);

    /** 按精确名称查找工具。 */
    Optional<ToolDefinition> find(String name);

    /** 返回按名称自然顺序排列的不可变定义列表。 */
    List<ToolDefinition> list();

    /** 执行一次受治理的工具调用。 */
    ToolResult execute(ToolCall call, ToolInvocationContext context);
}
