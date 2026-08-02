package com.agent.web.log;

import com.agent.core.trace.RunLogEvent;
import com.agent.core.trace.RunLogPublisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 按 Run 隔离多订阅者实时日志的进程内总线。 */
public final class InMemoryRunLogEventBus implements RunLogPublisher, AutoCloseable {

    private static final int BUFFER_CAPACITY = 1024;
    private static final Logger LOGGER =
            System.getLogger(InMemoryRunLogEventBus.class.getName());

    private final ConcurrentMap<UUID, ConcurrentMap<LogChannel, Boolean>> channels =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleMonitor = new Object();

    /** 订阅一个 Run 的实时日志，不重放订阅前的事件。 */
    public Flux<RunLogEvent> subscribe(UUID runId) {
        ensureOpen();
        Objects.requireNonNull(runId, "runId 不能为空");
        return Flux.defer(() -> openSubscription(runId).events());
    }

    /** 在读取快照前立即注册一个独立的有界订阅。 */
    public RunLogSubscription openSubscription(UUID runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        LogChannel channel = new LogChannel();
        synchronized (lifecycleMonitor) {
            ensureOpen();
            channels.computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>())
                    .put(channel, Boolean.TRUE);
        }
        return new RunLogSubscription(
                channel.flux(), () -> removeAndStop(runId, channel));
    }

    /** 向该 Run 的所有当前订阅者发布同一个不可变日志事件。 */
    @Override
    public void publish(RunLogEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        List<LogChannel> currentChannels;
        synchronized (lifecycleMonitor) {
            ensureOpen();
            ConcurrentMap<LogChannel, Boolean> runChannels = channels.get(event.runId());
            if (runChannels == null) {
                return;
            }
            currentChannels = List.copyOf(runChannels.keySet());
        }

        for (LogChannel channel : currentChannels) {
            Sinks.EmitResult result = channel.emit(event);
            if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                LOGGER.log(Level.ERROR,
                        "Run 日志缓冲区溢出: runId=" + event.runId());
                removeAndStop(event.runId(), channel);
            } else if (result.isFailure()) {
                removeAndStop(event.runId(), channel);
            }
        }
    }

    /** 排空缓冲后完成并移除指定 Run 的全部订阅。 */
    public void complete(UUID runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        ConcurrentMap<LogChannel, Boolean> runChannels;
        synchronized (lifecycleMonitor) {
            ensureOpen();
            runChannels = channels.remove(runId);
        }
        if (runChannels != null) {
            runChannels.keySet().forEach(LogChannel::complete);
        }
    }

    /** 立即完成全部订阅并拒绝后续发布或订阅。 */
    @Override
    public void close() {
        List<LogChannel> currentChannels = new ArrayList<>();
        synchronized (lifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            channels.values().forEach(runChannels ->
                    currentChannels.addAll(runChannels.keySet()));
            channels.clear();
        }
        currentChannels.forEach(LogChannel::stop);
    }

    private void removeAndStop(UUID runId, LogChannel channel) {
        synchronized (lifecycleMonitor) {
            ConcurrentMap<LogChannel, Boolean> runChannels = channels.get(runId);
            if (runChannels != null) {
                runChannels.remove(channel);
                if (runChannels.isEmpty()) {
                    channels.remove(runId, runChannels);
                }
            }
        }
        channel.stop();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("RunLogEventBus 已关闭");
        }
    }

    private static final class LogChannel {

        private final Sinks.Many<RunLogEvent> sink = Sinks.many()
                .unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(BUFFER_CAPACITY));
        private final Sinks.One<Void> stop = Sinks.one();

        private synchronized Sinks.EmitResult emit(RunLogEvent event) {
            return sink.tryEmitNext(event);
        }

        private synchronized void complete() {
            sink.tryEmitComplete();
        }

        private synchronized void stop() {
            stop.tryEmitEmpty();
        }

        private Flux<RunLogEvent> flux() {
            return sink.asFlux().takeUntilOther(stop.asMono());
        }
    }
}
