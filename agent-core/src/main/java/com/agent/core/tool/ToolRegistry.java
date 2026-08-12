package com.agent.core.tool;

import java.util.List;
import java.util.Optional;
import java.time.Duration;

/** 统一工具注册、治理、执行与审计端口。 */
public interface ToolRegistry extends AutoCloseable {

    /** 注册一个工具定义。 */
    void register(ToolDefinition definition);

    /** 在任何定义失败时不写入整批工具。 */
    void registerAll(List<ToolDefinition> definitions);

    /** 以 owner 为边界原子注册一批工具定义。 */
    default void registerOwned(String ownerId, List<ToolDefinition> definitions) {
        registerAll(definitions);
    }

    /** 将 owner 置为 drain 状态，拒绝该 owner 的后续调用。 */
    default void beginDrain(String ownerId) {
        throw new UnsupportedOperationException("当前 ToolRegistry 不支持 owner drain");
    }

    /** 等待 owner 的在途调用结束后撤销其全部工具。 */
    default void unregisterOwned(String ownerId, Duration timeout) {
        throw new UnsupportedOperationException("当前 ToolRegistry 不支持 owner 撤销");
    }

    /** 返回当前不可变注册快照的修订号。 */
    default long revision() {
        return 0;
    }

    /** 按精确名称查找工具。 */
    Optional<ToolDefinition> find(String name);

    /** 返回按名称自然顺序排列的不可变定义列表。 */
    List<ToolDefinition> list();

    /** 执行一次受治理的工具调用。 */
    ToolResult execute(ToolCall call, ToolInvocationContext context);
}
