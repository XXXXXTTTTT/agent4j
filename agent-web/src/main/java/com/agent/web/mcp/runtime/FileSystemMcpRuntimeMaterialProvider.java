package com.agent.web.mcp.runtime;

import com.agent.web.mcp.installation.McpSourceSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/** 从受配置物料根目录读取已持久化登记的离线物料。 */
public final class FileSystemMcpRuntimeMaterialProvider implements McpRuntimeMaterialProvider {
    private final Path materialRoot;
    private final Function<McpSourceSnapshot, PreparedMaterial> preparedMaterialLookup;

    public FileSystemMcpRuntimeMaterialProvider(
            Path materialRoot,
            Function<McpSourceSnapshot, PreparedMaterial> preparedMaterialLookup) {
        this.materialRoot = realDirectory(materialRoot);
        this.preparedMaterialLookup = Objects.requireNonNull(preparedMaterialLookup, "preparedMaterialLookup 不能为空");
    }

    @Override
    public PreparedMaterial requirePrepared(McpSourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        PreparedMaterial prepared;
        try {
            prepared = preparedMaterialLookup.apply(snapshot);
        } catch (RuntimeException exception) {
            throw materialMissing(snapshot);
        }
        if (prepared == null || !isValid(prepared)) {
            throw materialMissing(snapshot);
        }
        return prepared;
    }

    private boolean isValid(PreparedMaterial prepared) {
        try {
            Path directory = prepared.directory().toRealPath();
            if (!Files.isDirectory(directory) || !directory.startsWith(materialRoot) || Files.isSymbolicLink(prepared.directory())) {
                return false;
            }
            return McpRuntimeMaterialProvider.sha256(directory).equals(prepared.sha256());
        } catch (IOException | McpMaterialNotPreparedException exception) {
            return false;
        }
    }

    private static Path realDirectory(Path directory) {
        Objects.requireNonNull(directory, "materialRoot 不能为空");
        try {
            Path real = directory.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("materialRoot 必须是已有目录");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("materialRoot 必须是已有目录", exception);
        }
    }

    private static McpMaterialNotPreparedException materialMissing(McpSourceSnapshot snapshot) {
        return new McpMaterialNotPreparedException(snapshot.snapshotId());
    }
}
