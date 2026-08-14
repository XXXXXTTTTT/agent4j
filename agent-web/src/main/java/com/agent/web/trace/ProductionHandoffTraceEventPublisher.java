package com.agent.web.trace;

import com.agent.core.multiagent.AgentHandoffEvent;
import com.agent.core.multiagent.AgentHandoffEventPublisher;
import com.agent.core.trace.TraceEvent;
import com.agent.core.trace.TraceEventPublisher;

import java.util.Objects;
import java.util.UUID;

/** 将受治理 handoff 生命周期投影到父 Run 的 Trace 流。 */
public final class ProductionHandoffTraceEventPublisher
        implements AgentHandoffEventPublisher {

    private final TraceEventPublisher parentRunTracePublisher;

    /** 创建父 Run Trace 投影器。 */
    public ProductionHandoffTraceEventPublisher(
            TraceEventPublisher parentRunTracePublisher) {
        this.parentRunTracePublisher = Objects.requireNonNull(
                parentRunTracePublisher, "parentRunTracePublisher 不能为空");
    }

    /** 只发布 handoff 合同元数据，不携带子 Agent 的隐藏推理内容。 */
    @Override
    public void publish(AgentHandoffEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        parentRunTracePublisher.publish(new TraceEvent.Handoff(
                UUID.randomUUID(),
                event.parentRunId(),
                0,
                event.occurredAt(),
                event.taskId(),
                event.parentRunId(),
                event.childRunId(),
                event.fromAgent(),
                event.toAgent(),
                lifecycle(event)));
    }

    private static String lifecycle(AgentHandoffEvent event) {
        return switch (event) {
            case AgentHandoffEvent.Started ignored -> "STARTED";
            case AgentHandoffEvent.NodeStarted ignored -> "NODE_STARTED";
            case AgentHandoffEvent.NodeProgress ignored -> "NODE_PROGRESS";
            case AgentHandoffEvent.NodeCompleted ignored -> "NODE_COMPLETED";
            case AgentHandoffEvent.Completed ignored -> "COMPLETED";
            case AgentHandoffEvent.Failed ignored -> "FAILED";
        };
    }
}
