package com.agent.web.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/** 将会话审计事件序列化为北京时间 JSON Lines。 */
public final class Slf4jConversationAuditSink implements ConversationAuditSink {

    public static final String LOGGER_NAME = "com.agent.audit.conversation";
    private static final ZoneId AUDIT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(AUDIT_ZONE);
    private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    private final ObjectMapper objectMapper;
    private final AuditTextRedactor redactor;

    public Slf4jConversationAuditSink(ObjectMapper objectMapper) {
        this(objectMapper, new AuditTextRedactor(java.util.List.of()));
    }

    public Slf4jConversationAuditSink(
            ObjectMapper objectMapper,
            AuditTextRedactor redactor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.redactor = Objects.requireNonNull(redactor, "redactor 不能为空");
    }

    @Override
    public void record(ConversationAuditEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        ObjectNode json = objectMapper.createObjectNode();
        json.put("eventType", event.eventType().name());
        json.put("occurredAt", TIME_FORMATTER.format(event.occurredAt()));
        put(json, "userId", event.userId());
        put(json, "workspaceId", event.workspaceId());
        json.put("conversationId", event.conversationId().toString());
        put(json, "turnId", event.turnId());
        put(json, "runId", event.runId());
        put(json, "turnIndex", event.turnIndex());
        put(json, "status", event.status());
        put(json, "userContent", redactor.redact(event.userContent()));
        put(json, "assistantContent", redactor.redact(event.assistantContent()));
        put(json, "error", redactor.redact(event.error()));
        put(json, "durationMs", event.durationMs());
        try {
            LOGGER.info(objectMapper.writeValueAsString(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("会话审计事件序列化失败", exception);
        }
    }

    private static void put(ObjectNode json, String name, String value) {
        if (value == null) {
            json.putNull(name);
        } else {
            json.put(name, value);
        }
    }

    private static void put(ObjectNode json, String name, UUID value) {
        put(json, name, value == null ? null : value.toString());
    }

    private static void put(ObjectNode json, String name, Long value) {
        if (value == null) {
            json.putNull(name);
        } else {
            json.put(name, value);
        }
    }
}
