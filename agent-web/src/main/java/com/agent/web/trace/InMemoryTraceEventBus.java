package com.agent.web.trace;

import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 按 Run 隔离实时事件的进程内 Trace 总线。 */
public final class InMemoryTraceEventBus implements TraceEventPublisher, AutoCloseable {

    private static final int BUFFER_CAPACITY = 256;
    private static final Logger LOGGER =
            System.getLogger(InMemoryTraceEventBus.class.getName());

    private final ConcurrentMap<UUID, RunChannel> channels = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 订阅一个 Run 的实时事件。
     *
     * @param runId Run 标识
     * @return 不重放历史事件的单订阅者事件流
     */
    public Flux<TraceEvent> subscribe(UUID runId) {
        ensureOpen();
        Objects.requireNonNull(runId, "runId 不能为空");
        return Flux.defer(() -> register(runId));
    }

    /** 发布事件；没有当前订阅者时直接丢弃。 */
    @Override
    public void publish(TraceEvent event) {
        ensureOpen();
        Objects.requireNonNull(event, "event 不能为空");
        RunChannel channel = channels.get(event.runId());
        if (channel == null) {
            return;
        }

        Sinks.EmitResult result = channel.emit(event);
        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            LOGGER.log(Level.ERROR, "Trace 缓冲区溢出: runId=" + event.runId());
            removeAndComplete(event.runId(), channel);
            return;
        }
        if (result.isFailure()) {
            removeAndComplete(event.runId(), channel);
            return;
        }
        if (isTerminal(event)) {
            removeAndComplete(event.runId(), channel);
        }
    }

    /** 完成所有订阅并停止接收新事件。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        channels.forEach(this::removeAndComplete);
    }

    private Flux<TraceEvent> register(UUID runId) {
        ensureOpen();
        RunChannel channel = new RunChannel();
        RunChannel existing = channels.putIfAbsent(runId, channel);
        if (existing != null) {
            return Flux.error(new IllegalStateException(
                    "Run 已有 Trace 订阅: " + runId));
        }
        return channel.flux().doFinally(signal -> removeAndComplete(runId, channel));
    }

    private void removeAndComplete(UUID runId, RunChannel channel) {
        if (channels.remove(runId, channel)) {
            channel.complete();
        }
    }

    private static boolean isTerminal(TraceEvent event) {
        return event instanceof TraceEvent.Completed
                || event instanceof TraceEvent.Failed
                || event instanceof TraceEvent.Rejected;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("TraceEventBus 已关闭");
        }
    }

    private static final class RunChannel {

        private final Sinks.Many<TraceEvent> sink = Sinks.many()
                .unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(BUFFER_CAPACITY));

        private synchronized Sinks.EmitResult emit(TraceEvent event) {
            return sink.tryEmitNext(event);
        }

        private synchronized void complete() {
            sink.tryEmitComplete();
        }

        private Flux<TraceEvent> flux() {
            return sink.asFlux();
        }
    }
}
