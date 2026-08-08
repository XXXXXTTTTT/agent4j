package com.agent.rag.ingest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositorySourceScannerTest {

    @TempDir
    Path workspace;

    @Test
    void capturesSortedUtf8SourcesAndExcludesBuildOrBinaryContent() throws IOException {
        write("zeta.txt", "zeta");
        write("src/Alpha.java", "package src; final class Alpha {}\n");
        write("target/ignored.txt", "target");
        write("node_modules/ignored.txt", "node");
        write(".git/ignored.txt", "git");
        Files.write(workspace.resolve("binary.bin"), new byte[]{1, 0, 2});
        Files.write(workspace.resolve("invalid.txt"), new byte[]{(byte) 0xC3, 0x28});

        RepositorySnapshot snapshot = new RepositorySourceScanner().capture(workspace);

        assertThat(snapshot.root()).isEqualTo(workspace.toRealPath());
        assertThat(snapshot.sources())
                .extracting(RepositorySource::relativePath)
                .containsExactly("src/Alpha.java", "zeta.txt");
        assertThat(snapshot.sources().get(0).content())
                .isEqualTo("package src; final class Alpha {}\n");
        assertThat(snapshot.sources())
                .extracting(RepositorySource::contentSha256)
                .containsExactly(
                        sha256("package src; final class Alpha {}\n"),
                        sha256("zeta"));
        assertThat(snapshot.fingerprint()).isEqualTo(sha256(
                "src/Alpha.java\n" + sha256("package src; final class Alpha {}\n") + "\n"
                        + "zeta.txt\n" + sha256("zeta") + "\n"));
        assertThatThrownBy(() -> snapshot.sources().add(new RepositorySource(
                "other.txt", "other", sha256("other"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fingerprintsContentRatherThanModificationTime() throws IOException {
        Path source = write("notes.txt", "alpha");
        RepositorySourceScanner scanner = new RepositorySourceScanner();

        RepositorySnapshot first = scanner.capture(workspace);
        Files.setLastModifiedTime(source, FileTime.from(Instant.now().plusSeconds(60)));
        RepositorySnapshot touched = scanner.capture(workspace);
        Files.writeString(source, "beta", StandardCharsets.UTF_8);
        RepositorySnapshot changed = scanner.capture(workspace);

        assertThat(touched.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(changed.fingerprint()).isNotEqualTo(first.fingerprint());
    }

    @Test
    void rejectsSymbolicLinkWhoseRealTargetLeavesRepository() throws IOException {
        Path outside = Files.createTempFile("agent4j-outside-", ".txt");
        Path link = workspace.resolve("escape.txt");
        try {
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                Assumptions.abort("当前文件系统不支持创建测试符号链接: " + exception);
            }

            assertThatThrownBy(() -> new RepositorySourceScanner().capture(workspace))
                    .isInstanceOf(CodebaseIngestionException.class)
                    .hasMessageContaining("符号链接目标越过仓库根目录")
                    .hasMessageContaining("escape.txt");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void validatesSnapshotAndSourceContracts() {
        assertThatThrownBy(() -> new RepositorySource(
                "../escape.txt", "value", sha256("value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relativePath");
        assertThatThrownBy(() -> new RepositorySnapshot(
                workspace,
                List.of(),
                "UPPER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
    }

    private Path write(String relativePath, String content) throws IOException {
        Path path = workspace.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
