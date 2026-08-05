package com.agent.web.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/** 任务优先的生产 Code Agent 请求。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CodeAgentStartRequest(
        @NotBlank String task,
        String workspacePath,
        String repositoryId,
        String userId,
        String reviewerUrl) {
}
