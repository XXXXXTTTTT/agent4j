package com.agent.web.controller;

import com.agent.core.orchestration.AgentRole;
import com.agent.core.orchestration.OrchestrationMode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** 提交会话轮次请求。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SubmitConversationTurnRequest(
        @NotBlank String content,
        String reviewerUrl,
        String modelGroupId,
        OrchestrationMode orchestrationMode,
        Map<AgentRole, String> roleModelGroups) {

    /** 保留旧版三参数 Java 构造器，缺省为未选择编排模式。 */
    public SubmitConversationTurnRequest(String content, String reviewerUrl, String modelGroupId) {
        this(content, reviewerUrl, modelGroupId, null, Map.of());
    }

    public SubmitConversationTurnRequest {
        if (roleModelGroups == null || roleModelGroups.isEmpty()) {
            roleModelGroups = Map.of();
        } else {
            EnumMap<AgentRole, String> copy = new EnumMap<>(AgentRole.class);
            copy.putAll(roleModelGroups);
            roleModelGroups = Collections.unmodifiableMap(copy);
        }
    }
}
