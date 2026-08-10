package com.agent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 外部项目 ZIP 导入的资源上限。 */
@ConfigurationProperties(prefix = "agent.workspace-import")
public record WorkspaceImportProperties(
        long maxArchiveBytes,
        long maxExtractedBytes,
        int maxFiles) {

    public WorkspaceImportProperties {
        if (maxArchiveBytes < 1) {
            throw new IllegalArgumentException("maxArchiveBytes 必须大于 0");
        }
        if (maxExtractedBytes < 1) {
            throw new IllegalArgumentException("maxExtractedBytes 必须大于 0");
        }
        if (maxFiles < 1) {
            throw new IllegalArgumentException("maxFiles 必须大于 0");
        }
    }
}
