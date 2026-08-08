package com.agent.rag.ingest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** 扫描仓库内受路径边界约束的 UTF-8 源文件并生成内容指纹。 */
public final class RepositorySourceScanner {

    /** 捕获一次同源、稳定排序的仓库正文快照。 */
    public RepositorySnapshot capture(Path repositoryRoot) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot 不能为空");
        Path root = realRoot(repositoryRoot);
        List<RepositorySource> sources = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> !path.equals(root))
                    .filter(path -> !excluded(root, path))
                    .forEach(path -> capturePath(root, path, sources));
        } catch (CodebaseIngestionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CodebaseIngestionException("读取仓库文件失败: " + root, exception);
        }
        List<RepositorySource> sorted = sources.stream()
                .sorted(Comparator.comparing(RepositorySource::relativePath))
                .toList();
        return new RepositorySnapshot(root, sorted, fingerprint(sorted));
    }

    private Path realRoot(Path repositoryRoot) {
        try {
            Path root = repositoryRoot.toRealPath();
            if (!Files.isDirectory(root)) {
                throw new IOException("不是目录");
            }
            return root;
        } catch (IOException exception) {
            throw new CodebaseIngestionException("仓库根目录无效: " + repositoryRoot, exception);
        }
    }

    private void capturePath(
            Path root,
            Path path,
            List<RepositorySource> sources) {
        validateSymbolicLink(root, path);
        if (!Files.isRegularFile(path)) {
            return;
        }
        DecodedSource decoded = readUtf8OrSkip(path);
        if (decoded == null) {
            return;
        }
        sources.add(new RepositorySource(
                relativePath(root, path),
                decoded.content(),
                sha256(decoded.bytes())));
    }

    private void validateSymbolicLink(Path root, Path path) {
        if (!Files.isSymbolicLink(path)) {
            return;
        }
        try {
            Path target = path.toRealPath();
            if (!target.startsWith(root)) {
                throw new CodebaseIngestionException(
                        "符号链接目标越过仓库根目录: " + relativePath(root, path));
            }
        } catch (CodebaseIngestionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CodebaseIngestionException(
                    "解析符号链接失败: " + relativePath(root, path), exception);
        }
    }

    private DecodedSource readUtf8OrSkip(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            for (byte value : bytes) {
                if (value == 0) {
                    return null;
                }
            }
            try {
                String content = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
                return new DecodedSource(bytes, content);
            } catch (CharacterCodingException exception) {
                return null;
            }
        } catch (IOException exception) {
            throw new CodebaseIngestionException("读取文件失败: " + path, exception);
        }
    }

    private boolean excluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(".git") || name.equals("target") || name.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private String fingerprint(List<RepositorySource> sources) {
        StringBuilder material = new StringBuilder();
        for (RepositorySource source : sources) {
            material.append(source.relativePath())
                    .append('\n')
                    .append(source.contentSha256())
                    .append('\n');
        }
        return sha256(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private record DecodedSource(byte[] bytes, String content) {
    }
}
