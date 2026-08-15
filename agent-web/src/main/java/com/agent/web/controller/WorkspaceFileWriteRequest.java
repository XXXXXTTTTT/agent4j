package com.agent.web.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 写入工作区文本文件请求。 */
public record WorkspaceFileWriteRequest(
        @NotBlank String path,
        @NotNull String content,
        String expectedSha256) {
}
