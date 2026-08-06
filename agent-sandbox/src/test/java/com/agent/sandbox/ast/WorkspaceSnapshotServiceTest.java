package com.agent.sandbox.ast;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceSnapshotServiceTest {

    @TempDir
    Path workspace;

    @Test
    void capturesDeterministicTextFilesAndSkipsBuildAndVcsDirectories() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve("target"));
        Files.createDirectories(workspace.resolve("node_modules/pkg"));
        Files.writeString(workspace.resolve("src/App.java"), "class App {}\n");
        Files.writeString(workspace.resolve("README.md"), "readme\n");
        Files.writeString(workspace.resolve(".env"), "AGENT_LLM_API_KEY=secret\n");
        Files.writeString(workspace.resolve("client.pem"), "private certificate\n");
        Files.writeString(workspace.resolve("signing.key"), "private key\n");
        Files.writeString(workspace.resolve("target/generated.java"), "ignored\n");
        Files.writeString(workspace.resolve("node_modules/pkg/index.js"), "ignored\n");
        Files.write(workspace.resolve("image.bin"), new byte[] {0, 1, 2});
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 快照只验证 Git 工作树边界，不需要提交。
        }

        WorkspaceSnapshot snapshot = new WorkspaceSnapshotService(10, 1024).capture(workspace);

        assertThat(snapshot.root()).isEqualTo(workspace.toRealPath());
        assertThat(snapshot.files()).extracting(WorkspaceFile::relativePath)
                .containsExactly("README.md", "src/App.java");
        assertThat(snapshot.files()).extracting(WorkspaceFile::content)
                .containsExactly("readme\n", "class App {}\n");
        assertThat(snapshot.totalBytes()).isEqualTo("readme\nclass App {}\n".getBytes().length);
    }

    @Test
    void rejectsNonGitDirectoryAndConfiguredSizeOverflow() throws Exception {
        Files.writeString(workspace.resolve("value.txt"), "value\n");

        assertThatThrownBy(() -> new WorkspaceSnapshotService(10, 1024).capture(workspace))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("Git");

        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 初始化后再验证大小门禁。
        }
        assertThatThrownBy(() -> new WorkspaceSnapshotService(10, 1).capture(workspace))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("字节");
    }

    @Test
    void capturesBoundedPromptViewWithoutFailingOnRepositoryOverflow() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "1234");
        Files.writeString(workspace.resolve("b.txt"), "5678");
        Files.writeString(workspace.resolve("c.txt"), "90");
        try (Git ignored = Git.init().setDirectory(workspace.toFile()).call()) {
            // 初始化真实 Git 工作树后验证 Prompt 快照的有界截断语义。
        }

        WorkspaceSnapshot snapshot = new WorkspaceSnapshotService(2, 6)
                .captureForPrompt(workspace);

        assertThat(snapshot.files()).extracting(WorkspaceFile::relativePath)
                .containsExactly("a.txt", "c.txt");
        assertThat(snapshot.totalBytes()).isEqualTo(6);
    }
}
