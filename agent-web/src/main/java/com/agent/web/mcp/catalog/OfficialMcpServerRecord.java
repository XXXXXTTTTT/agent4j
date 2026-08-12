package com.agent.web.mcp.catalog;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 官方 MCP 服务的不可变目录快照。 */
public record OfficialMcpServerRecord(
        String serviceId,
        String sourcePath,
        URI sourceUrl,
        String commitSha,
        Map<String, String> blobShas,
        String version,
        String description,
        String license,
        String command,
        List<String> arguments,
        List<String> environmentVariableNames,
        String readmeSummary) {
    public OfficialMcpServerRecord {
        serviceId = require(serviceId, "serviceId");
        sourcePath = require(sourcePath, "sourcePath");
        sourceUrl = Objects.requireNonNull(sourceUrl, "sourceUrl 不能为空");
        commitSha = require(commitSha, "commitSha");
        blobShas = Map.copyOf(Objects.requireNonNull(blobShas, "blobShas 不能为空"));
        version = require(version, "version");
        description = require(description, "description");
        license = require(license, "license");
        command = require(command, "command");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments 不能为空"));
        environmentVariableNames = List.copyOf(Objects.requireNonNull(environmentVariableNames, "environmentVariableNames 不能为空"));
        readmeSummary = Objects.requireNonNullElse(readmeSummary, "");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
