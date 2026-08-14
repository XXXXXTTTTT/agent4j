package com.agent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Slash Command 全局目录和资源上限配置。 */
@ConfigurationProperties(prefix = "agent.commands")
public record CommandProperties(String globalDirectory, long maxFileBytes) {

    /** 冻结可选目录和文件大小上限。 */
    public CommandProperties {
        globalDirectory = globalDirectory == null ? "" : globalDirectory.trim();
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("maxFileBytes 必须大于 0");
        }
    }
}
