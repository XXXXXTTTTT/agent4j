package com.agent.core.trace;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Run 中一个节点发布的实时终端日志片段。
 *
 * @param eventId 事件标识
 * @param runId Run 标识
 * @param nodeName 节点精确名称
 * @param sequence 本次节点执行内从 0 开始的序号
 * @param stream 日志流
 * @param text 原始日志文本
 * @param occurredAt 事件时间
 */
public record RunLogEvent(
        UUID eventId,
        UUID runId,
        String nodeName,
        long sequence,
        RunLogStream stream,
        String text,
        Instant occurredAt) {

    /** 校验实时日志事件。 */
    public RunLogEvent {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName 不能为空");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence 不能小于 0");
        }
        Objects.requireNonNull(stream, "stream 不能为空");
        Objects.requireNonNull(text, "text 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
    }
}
