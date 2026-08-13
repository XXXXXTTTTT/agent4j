package com.agent.web.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** 任务优先的生产 Code Agent 请求。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CodeAgentStartRequest(
        @NotBlank String task,
        @NotNull UUID workspaceId,
        String repositoryId,
        String reviewerUrl) {
}
