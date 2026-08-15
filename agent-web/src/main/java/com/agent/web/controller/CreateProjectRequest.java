package com.agent.web.controller;

import jakarta.validation.constraints.NotBlank;

/** 创建空项目请求。 */
public record CreateProjectRequest(
        @NotBlank String displayName,
        @NotBlank String directoryName,
        @NotBlank String repositoryId) {
}
