package com.agent.web.mcp.installation;

import com.agent.web.mcp.catalog.OfficialMcpServerRecord;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 已确认 MCP 的不可变官方源快照。 */
public record McpSourceSnapshot(
        UUID snapshotId,
        String serverKey,
        String repositoryPath,
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
        String readmeSummary,
        Instant createdAt) {
    public McpSourceSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
        serverKey = required(serverKey, "serverKey");
        repositoryPath = required(repositoryPath, "repositoryPath");
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
        createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    /** 由固定 Git commit 的官方目录记录创建快照。 */
    public static McpSourceSnapshot from(UUID snapshotId, OfficialMcpServerRecord server, Instant createdAt) {
        Objects.requireNonNull(server, "server 不能为空");
        return new McpSourceSnapshot(snapshotId, server.serviceId(), server.sourcePath(), server.sourceUrl(),
                server.commitSha(), server.blobShas(), server.metadataSha256(), server.version(),
                server.description(), server.license(), server.command(), server.arguments(), server.launchBin(),
                server.environmentVariableNames(), server.readmeSummary(), createdAt);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
