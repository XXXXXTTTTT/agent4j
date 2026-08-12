package com.agent.web.mcp.installation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 已由受治理准备流程写入的 MCP 离线运行物料记录。 */
public record McpPreparedMaterialRecord(
        Path directory,
        String sha256,
        String command,
        List<String> arguments,
        Instant preparedAt) {
    public McpPreparedMaterialRecord {
        directory = Objects.requireNonNull(directory, "directory 不能为空");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 必须是 64 位小写十六进制");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments 不能为空"));
        preparedAt = Objects.requireNonNull(preparedAt, "preparedAt 不能为空");
    }
}
