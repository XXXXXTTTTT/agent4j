package com.agent.web.mcp.catalog;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 官方 MCP 服务在固定 Git commit 下的不可变元数据快照。 */
public record OfficialMcpServerRecord(
        String serviceId,
        String sourcePath,
        URI sourceUrl,
        String commitSha,
        Map<String, String> blobShas,
        String metadataSha256,
        String version,
        String description,
        String license,
        String command,
        List<String> arguments,
        String launchBin,
        List<String> environmentVariableNames,
        String readmeSummary) {
    public OfficialMcpServerRecord {
        serviceId = required(serviceId, "serviceId");
        sourcePath = required(sourcePath, "sourcePath");
        sourceUrl = Objects.requireNonNull(sourceUrl, "sourceUrl 不能为空");
        commitSha = required(commitSha, "commitSha");
        blobShas = Map.copyOf(Objects.requireNonNull(blobShas, "blobShas 不能为空"));
        metadataSha256 = required(metadataSha256, "metadataSha256");
        version = required(version, "version");
        description = Objects.requireNonNullElse(description, "");
        license = Objects.requireNonNullElse(license, "");
        command = required(command, "command");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments 不能为空"));
        launchBin = required(launchBin, "launchBin");
        environmentVariableNames = List.copyOf(Objects.requireNonNull(environmentVariableNames, "environmentVariableNames 不能为空"));
        readmeSummary = Objects.requireNonNullElse(readmeSummary, "");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value;
    }
}
