package com.agent.web.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** 将不含正文的工作区文件审计事件写入 JSON Lines 审计 logger。 */
public final class Slf4jWorkspaceFileAuditSink implements WorkspaceFileAuditSink {
    private static final ZoneId AUDIT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(AUDIT_ZONE);
    private static final Logger LOGGER = LoggerFactory.getLogger("com.agent.audit.workspace");
    private final ObjectMapper objectMapper;

    public Slf4jWorkspaceFileAuditSink(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public void record(WorkspaceFileAuditEvent event) {
        try {
            var json = objectMapper.createObjectNode();
            json.put("eventType", event.eventType().name());
            json.put("occurredAt", TIME_FORMATTER.format(event.occurredAt()));
            json.put("userId", event.userId());
            json.put("workspaceId", event.workspaceId().toString());
            json.put("path", event.path());
            json.put("bytes", event.bytes());
            if (event.sha256() == null) {
                json.putNull("sha256");
            } else {
                json.put("sha256", event.sha256());
            }
            json.put("result", event.result());
            LOGGER.info(objectMapper.writeValueAsString(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作区文件审计序列化失败", exception);
        }
    }
}
