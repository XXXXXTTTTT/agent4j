package com.agent.web.mcp.installation;

import java.util.List;
import java.util.Objects;

/** 运行 MCP 生命周期所需的同一安装聚合快照。 */
public record McpInstallationAggregate(
        McpInstallationRecord installation,
        McpSourceSnapshot snapshot,
        McpPreparedMaterialRecord material,
        List<McpToolBindingRecord> bindings) {
    public McpInstallationAggregate {
        installation = Objects.requireNonNull(installation, "installation 不能为空");
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        if (!installation.snapshotId().equals(snapshot.snapshotId())) {
            throw new IllegalArgumentException("installation.snapshotId 必须与 snapshot.snapshotId 一致");
        }
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings 不能为空"));
    }
}
