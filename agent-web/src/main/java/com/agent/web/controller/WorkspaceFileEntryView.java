package com.agent.web.controller;

import com.agent.web.workspace.WorkspaceFileEntry;

import java.time.Instant;
import java.util.Objects;

/** 工作区目录项 HTTP 视图。 */
public record WorkspaceFileEntryView(
        String name,
        String path,
        String kind,
        long size,
        Instant lastModified) {

    public static WorkspaceFileEntryView from(WorkspaceFileEntry entry) {
        Objects.requireNonNull(entry, "entry 不能为空");
        return new WorkspaceFileEntryView(entry.name(), entry.path(), entry.kind().name(),
                entry.size(), entry.lastModified());
    }
}
