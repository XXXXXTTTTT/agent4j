package com.agent.web.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** 将不含正文的工作区文件审计事件写入 JSON Lines 审计 logger。 */
public final class Slf4jWorkspaceFileAuditSink implements WorkspaceFileAuditSink {
    private static final Logger LOGGER = LoggerFactory.getLogger("com.agent.audit.workspace");
    private final ObjectMapper objectMapper;

    public Slf4jWorkspaceFileAuditSink(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public void record(WorkspaceFileAuditEvent event) {
        try {
            LOGGER.info(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作区文件审计序列化失败", exception);
        }
    }
}
