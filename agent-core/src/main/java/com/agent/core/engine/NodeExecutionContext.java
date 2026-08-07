package com.agent.core.engine;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * 节点执行所属的 Run 与节点上下文。
 *
 * @param runId Run 标识
 * @param nodeName 当前节点精确名称
 */
public record NodeExecutionContext(UUID runId, String nodeName) {

    private static final ThreadLocal<NodeExecutionContext> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Consumer<String>> PROGRESS = new ThreadLocal<>();
    private static final ThreadLocal<AtomicLong> TOKENS = new ThreadLocal<>();
    private static final ThreadLocal<LongConsumer> TOKEN_LIMIT = new ThreadLocal<>();
    private static final ThreadLocal<Runnable> PROGRESS_CLOCK = new ThreadLocal<>();

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
        Runnable progressClock = PROGRESS_CLOCK.get();
        if (progressClock != null) {
            progressClock.run();
        }
        if (publisher != null) {
            publisher.accept(summary);
        }
    }

    /** 累计当前节点产生的模型 token，并交给预算检查器。 */
    public static void consumeTokens(long tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens 不能小于 0");
        }
        AtomicLong counter = TOKENS.get();
        if (counter == null) {
            throw new IllegalStateException("当前没有节点执行上下文");
        }
        long total;
        try {
            total = Math.addExact(counter.get(), tokens);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("token 累计值溢出", exception);
        }
        counter.set(total);
        LongConsumer limit = TOKEN_LIMIT.get();
        if (limit != null) {
            limit.accept(total);
        }
    }

    /** 返回当前节点已经累计的 token 数；上下文外固定返回 0。 */
    public static long consumedTokens() {
        AtomicLong counter = TOKENS.get();
        return counter == null ? 0 : counter.get();
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
        return callWithin(context, progressPublisher, ignored -> { }, () -> { }, callable);
    }

    /** 在节点上下文中绑定 token 检查器和进度时钟。 */
    static <T> T callWithin(
            NodeExecutionContext context,
            Consumer<String> progressPublisher,
            LongConsumer tokenLimit,
            Runnable progressClock,
            Callable<T> callable)
            throws Exception {
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(progressPublisher, "progressPublisher 不能为空");
        Objects.requireNonNull(tokenLimit, "tokenLimit 不能为空");
        Objects.requireNonNull(progressClock, "progressClock 不能为空");
        Objects.requireNonNull(callable, "callable 不能为空");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("不允许嵌套绑定节点上下文");
        }
        CURRENT.set(context);
        PROGRESS.set(progressPublisher);
        TOKENS.set(new AtomicLong());
        TOKEN_LIMIT.set(tokenLimit);
        PROGRESS_CLOCK.set(progressClock);
        MDC.put("runId", context.runId().toString());
        MDC.put("traceId", context.runId().toString());
        MDC.put("nodeName", context.nodeName());
        try {
            return callable.call();
        } finally {
            CURRENT.remove();
            PROGRESS.remove();
            TOKENS.remove();
            TOKEN_LIMIT.remove();
            PROGRESS_CLOCK.remove();
            MDC.remove("runId");
            MDC.remove("traceId");
            MDC.remove("nodeName");
        }
    }
}
