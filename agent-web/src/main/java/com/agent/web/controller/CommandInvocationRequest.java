package com.agent.web.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Slash Command 分发请求。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CommandInvocationRequest(
        @NotBlank String input,
        @NotNull UUID conversationId,
        String modelGroupId) {

    /** 冻结可选模型组文本。 */
    public CommandInvocationRequest {
        modelGroupId = modelGroupId == null ? "" : modelGroupId.trim();
    }
}
