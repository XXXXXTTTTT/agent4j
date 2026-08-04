package com.agent.web.trace;

import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;
import com.agent.web.log.InMemoryRunLogEventBus;

import java.util.List;
import java.util.Objects;

/** 组合 Trace 发布与终态日志流清理。 */
public final class RunLifecycleEventPublisher implements TraceEventPublisher {

    private final List<TraceEventPublisher> tracePublishers;
    private final InMemoryRunLogEventBus logBus;

    /** 创建生命周期事件发布器。 */
    public RunLifecycleEventPublisher(
            TraceEventPublisher tracePublisher,
            InMemoryRunLogEventBus logBus) {
        this(List.of(tracePublisher), logBus);
    }

    /** 创建按注入顺序发布的不可变生命周期事件发布器。 */
    public RunLifecycleEventPublisher(
            List<TraceEventPublisher> tracePublishers,
            InMemoryRunLogEventBus logBus) {
        Objects.requireNonNull(tracePublishers, "tracePublishers 不能为空");
        if (tracePublishers.isEmpty()) {
            throw new IllegalArgumentException("tracePublishers 不能为空列表");
        }
        this.tracePublishers = List.copyOf(tracePublishers);
        this.tracePublishers.forEach(
                publisher -> Objects.requireNonNull(publisher, "tracePublisher 不能为空"));
        this.logBus = Objects.requireNonNull(logBus, "logBus 不能为空");
    }

    /** 先发布 Trace，并在终态事件后完成对应日志流。 */
    @Override
    public void publish(TraceEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        boolean terminal = event instanceof TraceEvent.Completed
                || event instanceof TraceEvent.Failed
                || event instanceof TraceEvent.Rejected;
        RuntimeException primaryFailure = null;
        for (TraceEventPublisher publisher : tracePublishers) {
            try {
                publisher.publish(event);
            } catch (RuntimeException exception) {
                if (primaryFailure == null) {
                    primaryFailure = exception;
                } else {
                    primaryFailure.addSuppressed(exception);
                }
            }
        }
        if (terminal) {
            try {
                logBus.complete(event.runId());
            } catch (RuntimeException exception) {
                if (primaryFailure == null) {
                    primaryFailure = exception;
                } else {
                    primaryFailure.addSuppressed(exception);
                }
            }
        }
        if (primaryFailure != null) {
            throw primaryFailure;
        }
    }
}
