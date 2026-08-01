package com.agent.core.trace;

import com.agent.core.engine.InterruptRequest;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Run 生命周期中的强类型 Trace 事件。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TraceEvent.NodeStarted.class, name = "NODE_STARTED"),
        @JsonSubTypes.Type(value = TraceEvent.NodeCompleted.class, name = "NODE_COMPLETED"),
        @JsonSubTypes.Type(value = TraceEvent.Interrupted.class, name = "INTERRUPTED"),
        @JsonSubTypes.Type(value = TraceEvent.Approved.class, name = "APPROVED"),
        @JsonSubTypes.Type(value = TraceEvent.Rejected.class, name = "REJECTED"),
        @JsonSubTypes.Type(value = TraceEvent.Failed.class, name = "FAILED"),
        @JsonSubTypes.Type(value = TraceEvent.Completed.class, name = "COMPLETED")
})
public sealed interface TraceEvent
        permits TraceEvent.NodeStarted,
                TraceEvent.NodeCompleted,
                TraceEvent.Interrupted,
                TraceEvent.Approved,
                TraceEvent.Rejected,
                TraceEvent.Failed,
                TraceEvent.Completed {

    /** 返回事件标识。 */
    UUID eventId();

    /** 返回 Run 标识。 */
    UUID runId();

    /** 返回事件对应的 Checkpoint 版本。 */
    long checkpointVersion();

    /** 返回事件发生时间。 */
    Instant occurredAt();

    /** 返回强类型事件类别。 */
    @JsonIgnore
    default TraceEventType type() {
        return switch (this) {
            case NodeStarted ignored -> TraceEventType.NODE_STARTED;
            case NodeCompleted ignored -> TraceEventType.NODE_COMPLETED;
            case Interrupted ignored -> TraceEventType.INTERRUPTED;
            case Approved ignored -> TraceEventType.APPROVED;
            case Rejected ignored -> TraceEventType.REJECTED;
            case Failed ignored -> TraceEventType.FAILED;
            case Completed ignored -> TraceEventType.COMPLETED;
        };
    }

    /** 节点开始执行。 */
    record NodeStarted(
            UUID eventId,
            UUID runId,
            long checkpointVersion,
            Instant occurredAt,
            String nodeName) implements TraceEvent {

        /** 校验节点开始事件。 */
        public NodeStarted {
            validateCommon(eventId, runId, checkpointVersion, occurredAt);
            requireText(nodeName, "nodeName");
        }
    }

    /** 节点完成并解析出下一节点。 */
    record NodeCompleted(
            UUID eventId,
            UUID runId,
            long checkpointVersion,
            Instant occurredAt,
            String nodeName,
            String nextNode) implements TraceEvent {

        /** 校验节点完成事件。 */
        public NodeCompleted {
            validateCommon(eventId, runId, checkpointVersion, occurredAt);
            requireText(nodeName, "nodeName");
            requireText(nextNode, "nextNode");
        }
    }

    /** 节点执行前挂起。 */
    record Interrupted(
            UUID eventId,
            UUID runId,
            long checkpointVersion,
            Instant occurredAt,
            String nodeName,
            InterruptRequest request) implements TraceEvent {

        /** 校验中断事件。 */
        public Interrupted {
            validateCommon(eventId, runId, checkpointVersion, occurredAt);
            requireText(nodeName, "nodeName");
            Objects.requireNonNull(request, "request 不能为空");
            if (!nodeName.equals(request.nodeName())) {
                throw new IllegalArgumentException("中断事件节点名称不一致");
            }
        }
    }

    /** 人工批准中断。 */
    record Approved(
            UUID eventId,
            UUID runId,
            long checkpointVersion,
            Instant occurredAt,
            String nodeName,
            String reason) implements TraceEvent {

        /** 校验批准事件。 */
        public Approved {
            validateCommon(eventId, runId, checkpointVersion, occurredAt);
            requireText(nodeName, "nodeName");
            requireText(reason, "reason");
        }
    }

    /** 人工拒绝中断。 */
    record Rejected(
            UUID eventId,
            UUID runId,
            long checkpointVersion,
            Instant occurredAt,
            String nodeName,
            String reason) implements TraceEvent {

        /** 校验拒绝事件。 */
        public Rejected {
            validateCommon(eventId, runId, checkpointVersion, occurredAt);
            requireText(nodeName, "nodeName");
            requireText(reason, "reason");
        }
    }

    /** Run 执行失败。 */
    record Failed(
            UUID eventId,
            UUID runId,
            long checkpointVersion,
            Instant occurredAt,
            String error) implements TraceEvent {

        /** 校验失败事件。 */
        public Failed {
            validateCommon(eventId, runId, checkpointVersion, occurredAt);
            requireText(error, "error");
        }
    }

    /** Run 正常完成。 */
    record Completed(
            UUID eventId,
            UUID runId,
            long checkpointVersion,
            Instant occurredAt) implements TraceEvent {

        /** 校验完成事件。 */
        public Completed {
            validateCommon(eventId, runId, checkpointVersion, occurredAt);
        }
    }

    private static void validateCommon(
            UUID eventId,
            UUID runId,
            long checkpointVersion,
            Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        if (checkpointVersion < 0) {
            throw new IllegalArgumentException("checkpointVersion 不能小于 0");
        }
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
