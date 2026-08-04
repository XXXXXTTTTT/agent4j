package com.agent.core.engine;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * 节点执行所属的 Run 与节点上下文。
 *
 * @param runId Run 标识
 * @param nodeName 当前节点精确名称
 */
public record NodeExecutionContext(UUID runId, String nodeName) {

    private static final ThreadLocal<NodeExecutionContext> CURRENT = new ThreadLocal<>();

    /** 校验节点执行上下文。 */
    public NodeExecutionContext {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName 不能为空");
        }
    }

    /** 返回当前虚拟线程绑定的节点上下文。 */
    public static Optional<NodeExecutionContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 在当前线程绑定上下文，并保证退出时清理。 */
    static <T> T callWithin(NodeExecutionContext context, Callable<T> callable)
            throws Exception {
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(callable, "callable 不能为空");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("不允许嵌套绑定节点上下文");
        }
        CURRENT.set(context);
        try {
            return callable.call();
        } finally {
            CURRENT.remove();
        }
    }
}
