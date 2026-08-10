package com.agent.web.controller;

import com.agent.web.workspace.WorkspaceDirectoryEntry;
import com.agent.web.workspace.WorkspaceDirectoryListing;

import java.nio.file.Path;
import java.util.List;

/** 工作区目录浏览 HTTP 视图。 */
public record WorkspaceDirectoryView(
        String currentPath,
        String parentPath,
        List<WorkspaceDirectoryEntry> entries) {

    public static WorkspaceDirectoryView from(WorkspaceDirectoryListing listing) {
        return new WorkspaceDirectoryView(
                serverPath(listing.currentPath()),
                listing.parentPath() == null ? null : serverPath(listing.parentPath()),
                listing.entries().stream()
                        .map(path -> new WorkspaceDirectoryEntry(
                                path.getFileName().toString(), serverPath(path)))
                        .toList());
    }

    private static String serverPath(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }
}
