package com.agent.web.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/** 提交会话轮次请求。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SubmitConversationTurnRequest(
        @NotBlank String content,
        String reviewerUrl,
        String modelGroupId) {
}
