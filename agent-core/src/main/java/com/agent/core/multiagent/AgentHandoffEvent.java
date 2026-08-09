package com.agent.core.multiagent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Agent Handoff 独立子运行的强类型 Trace 事件。 */
public sealed interface AgentHandoffEvent
        permits AgentHandoffEvent.Started,
                AgentHandoffEvent.NodeStarted,
                AgentHandoffEvent.NodeProgress,
                AgentHandoffEvent.NodeCompleted,
                AgentHandoffEvent.Completed,
                AgentHandoffEvent.Failed {

    UUID taskId();

    UUID parentRunId();

    UUID childRunId();

    String fromAgent();

    String toAgent();

    Instant occurredAt();

    record Started(
            UUID taskId,
            UUID parentRunId,
            UUID childRunId,
            String fromAgent,
            String toAgent,
            Instant occurredAt) implements AgentHandoffEvent {

        public Started {
            validateCommon(taskId, parentRunId, childRunId, fromAgent, toAgent, occurredAt);
        }
    }

    record NodeStarted(
            UUID taskId,
            UUID parentRunId,
            UUID childRunId,
            String fromAgent,
            String toAgent,
            Instant occurredAt,
            String nodeName) implements AgentHandoffEvent {

        public NodeStarted {
            validateCommon(taskId, parentRunId, childRunId, fromAgent, toAgent, occurredAt);
            requireText(nodeName, "nodeName");
        }
    }

    record NodeProgress(
            UUID taskId,
            UUID parentRunId,
            UUID childRunId,
            String fromAgent,
            String toAgent,
            Instant occurredAt,
            String nodeName,
            String summary) implements AgentHandoffEvent {

        public NodeProgress {
            validateCommon(taskId, parentRunId, childRunId, fromAgent, toAgent, occurredAt);
            requireText(nodeName, "nodeName");
            requireText(summary, "summary");
        }
    }

    record NodeCompleted(
            UUID taskId,
            UUID parentRunId,
            UUID childRunId,
            String fromAgent,
            String toAgent,
            Instant occurredAt,
            String nodeName,
            String nextNode) implements AgentHandoffEvent {

        public NodeCompleted {
            validateCommon(taskId, parentRunId, childRunId, fromAgent, toAgent, occurredAt);
            requireText(nodeName, "nodeName");
            requireText(nextNode, "nextNode");
        }
    }

    record Completed(
            UUID taskId,
            UUID parentRunId,
            UUID childRunId,
            String fromAgent,
            String toAgent,
            Instant occurredAt,
            Duration elapsed) implements AgentHandoffEvent {

        public Completed {
            validateCommon(taskId, parentRunId, childRunId, fromAgent, toAgent, occurredAt);
            Objects.requireNonNull(elapsed, "elapsed 不能为空");
            if (elapsed.isNegative()) {
                throw new IllegalArgumentException("elapsed 不能为负数");
            }
        }
    }

    record Failed(
            UUID taskId,
            UUID parentRunId,
            UUID childRunId,
            String fromAgent,
            String toAgent,
            Instant occurredAt,
            String error) implements AgentHandoffEvent {

        public Failed {
            validateCommon(taskId, parentRunId, childRunId, fromAgent, toAgent, occurredAt);
            requireText(error, "error");
        }
    }

    private static void validateCommon(
            UUID taskId,
            UUID parentRunId,
            UUID childRunId,
            String fromAgent,
            String toAgent,
            Instant occurredAt) {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(parentRunId, "parentRunId 不能为空");
        Objects.requireNonNull(childRunId, "childRunId 不能为空");
        if (parentRunId.equals(childRunId)) {
            throw new IllegalArgumentException("childRunId 必须与 parentRunId 不同");
        }
        requireText(fromAgent, "fromAgent");
        requireText(toAgent, "toAgent");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
