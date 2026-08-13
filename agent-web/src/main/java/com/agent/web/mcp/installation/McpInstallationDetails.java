package com.agent.web.mcp.installation;

import java.util.List;
import java.util.Objects;

/** 面向工作台的已安装 MCP 固定快照元数据，不包含环境变量值。 */
public record McpInstallationDetails(
        McpInstallationRecord installation,
        List<String> environmentVariableNames) {
    public McpInstallationDetails {
        installation = Objects.requireNonNull(installation, "installation 不能为空");
        environmentVariableNames = List.copyOf(Objects.requireNonNull(
                environmentVariableNames, "environmentVariableNames 不能为空"));
    }
}
