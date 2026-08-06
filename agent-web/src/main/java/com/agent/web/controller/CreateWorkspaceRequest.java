package com.agent.web.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/** 创建工作区请求。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateWorkspaceRequest(
        @NotBlank String displayName,
        @NotBlank String workspacePath,
        @NotBlank String repositoryId) {
}
