package com.agent.web.controller;

import com.agent.web.workspace.WorkspaceFileContent;

import java.time.Instant;
import java.util.Objects;

/** 工作区文本内容 HTTP 视图。 */
public record WorkspaceFileContentView(
        String path,
        String content,
        String sha256,
        Instant lastModified) {

    public static WorkspaceFileContentView from(WorkspaceFileContent value) {
        Objects.requireNonNull(value, "content 不能为空");
        return new WorkspaceFileContentView(value.path(), value.content(), value.sha256(),
                value.lastModified());
    }
}
