package com.agent.core.engine;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * 节点执行所属的 Run 与节点上下文。
 *
 * @param runId Run 标识
 * @param nodeName 当前节点精确名称
 */
public record NodeExecutionContext(UUID runId, String nodeName) {

    private static final ThreadLocal<NodeExecutionContext> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Consumer<String>> PROGRESS = new ThreadLocal<>();

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

    /** 发布当前节点的过程摘要；没有图监听器时安全丢弃。 */
    public static void progress(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary 不能为空");
        }
        Consumer<String> publisher = PROGRESS.get();
        if (publisher != null) {
            publisher.accept(summary);
        }
    }

    /** 在当前线程绑定上下文，并保证退出时清理。 */
    static <T> T callWithin(NodeExecutionContext context, Callable<T> callable)
            throws Exception {
        return callWithin(context, ignored -> { }, callable);
    }

    /** 在当前线程绑定上下文、MDC 和过程事件发布器，并保证退出时清理。 */
    static <T> T callWithin(
            NodeExecutionContext context,
            Consumer<String> progressPublisher,
            Callable<T> callable)
            throws Exception {
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(progressPublisher, "progressPublisher 不能为空");
        Objects.requireNonNull(callable, "callable 不能为空");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("不允许嵌套绑定节点上下文");
        }
        CURRENT.set(context);
        PROGRESS.set(progressPublisher);
        MDC.put("runId", context.runId().toString());
        MDC.put("traceId", context.runId().toString());
        MDC.put("nodeName", context.nodeName());
        try {
            return callable.call();
        } finally {
            CURRENT.remove();
            PROGRESS.remove();
            MDC.remove("runId");
            MDC.remove("traceId");
            MDC.remove("nodeName");
        }
    }
}
