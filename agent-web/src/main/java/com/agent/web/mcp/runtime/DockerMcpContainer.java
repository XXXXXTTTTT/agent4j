package com.agent.web.mcp.runtime;

import java.util.UUID;

/** Docker 标签解析后的受管 MCP 容器摘要。 */
public record DockerMcpContainer(String containerId, UUID installationId, UUID snapshotId, boolean running) {
    public DockerMcpContainer {
        if (containerId == null || containerId.isBlank()) throw new IllegalArgumentException("containerId 不能为空");
        if (installationId == null) throw new IllegalArgumentException("installationId 不能为空");
        if (snapshotId == null) throw new IllegalArgumentException("snapshotId 不能为空");
    }
}
