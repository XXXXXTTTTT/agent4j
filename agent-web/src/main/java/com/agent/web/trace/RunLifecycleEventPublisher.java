package com.agent.web.trace;

import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;
import com.agent.web.log.InMemoryRunLogEventBus;

import java.util.Objects;

/** 组合 Trace 发布与终态日志流清理。 */
public final class RunLifecycleEventPublisher implements TraceEventPublisher {

    private final TraceEventPublisher tracePublisher;
    private final InMemoryRunLogEventBus logBus;

    /** 创建生命周期事件发布器。 */
    public RunLifecycleEventPublisher(
            TraceEventPublisher tracePublisher,
            InMemoryRunLogEventBus logBus) {
        this.tracePublisher = Objects.requireNonNull(
                tracePublisher, "tracePublisher 不能为空");
        this.logBus = Objects.requireNonNull(logBus, "logBus 不能为空");
    }

    /** 先发布 Trace，并在终态事件后完成对应日志流。 */
    @Override
    public void publish(TraceEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        boolean terminal = event instanceof TraceEvent.Completed
                || event instanceof TraceEvent.Failed
                || event instanceof TraceEvent.Rejected;
        try {
            tracePublisher.publish(event);
        } finally {
            if (terminal) {
                logBus.complete(event.runId());
            }
        }
    }
}
