package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpSourceSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 读取已经由受治理入口准备并校验过的 MCP 运行物料。 */
@FunctionalInterface
public interface McpRuntimeMaterialProvider {

    /** 返回与固定源快照一致的物料；未准备或校验失败时拒绝启动。 */
    PreparedMaterial requirePrepared(McpSourceSnapshot snapshot);

    /** 已准备物料的固定目录、摘要和离线入口。 */
    record PreparedMaterial(Path directory, String sha256, String command, List<String> arguments) {
        public PreparedMaterial {
            directory = Objects.requireNonNull(directory, "directory 不能为空");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 必须是 64 位小写十六进制");
            }
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("command 不能为空");
            }
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments 不能为空"));
        }
    }

    /** 计算物料树的稳定 SHA-256，不跟随符号链接。 */
    static String sha256(Path directory) {
        try {
            if (Files.isSymbolicLink(directory)) {
                throw new McpMaterialNotPreparedException(null);
            }
            Path root = directory.toRealPath();
            if (!Files.isDirectory(root)) {
                throw new IOException("物料目录不存在");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(root)) {
                List<Path> materialPaths = paths.toList();
                if (materialPaths.stream().anyMatch(Files::isSymbolicLink)) {
                    throw new McpMaterialNotPreparedException(null);
                }
                materialPaths.stream().filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                        .forEach(path -> updateDigest(digest, root, path));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (McpMaterialNotPreparedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new McpMaterialNotPreparedException(null);
        }
    }

    private static void updateDigest(MessageDigest digest, Path root, Path path) {
        try {
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (relative.isBlank() || Path.of(relative).isAbsolute() || relative.contains("..")) {
                throw new McpMaterialNotPreparedException(null);
            }
            String fileSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            digest.update((relative + "\n" + fileSha + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (McpMaterialNotPreparedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new McpMaterialNotPreparedException(null);
        }
    }
}
