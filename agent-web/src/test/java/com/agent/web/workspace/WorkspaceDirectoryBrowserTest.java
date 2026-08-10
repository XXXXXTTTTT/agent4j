package com.agent.web.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceDirectoryBrowserTest {

    @TempDir
    Path temp;

    @Test
    void listsOnlyRealDirectoriesInsideConfiguredRoot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Files.createDirectory(root.resolve("project"));
        Files.writeString(root.resolve("README.md"), "readme");
        WorkspaceDirectoryBrowser browser = new WorkspaceDirectoryBrowser(root);

        WorkspaceDirectoryListing listing = browser.browse(root);

        assertThat(listing.currentPath()).isEqualTo(root.toRealPath());
        assertThat(listing.parentPath()).isNull();
        assertThat(listing.entries()).extracting(path -> path.getFileName().toString())
                .containsExactly("project");
    }

    @Test
    void rejectsPathOutsideConfiguredRoot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        WorkspaceDirectoryBrowser browser = new WorkspaceDirectoryBrowser(root);

        assertThatThrownBy(() -> browser.browse(outside))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workspacePath 必须位于配置工作区内");
    }

    @Test
    void reportsParentForNestedDirectory() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path project = Files.createDirectory(root.resolve("project"));
        WorkspaceDirectoryBrowser browser = new WorkspaceDirectoryBrowser(root);

        WorkspaceDirectoryListing listing = browser.browse(project);

        assertThat(listing.currentPath()).isEqualTo(project.toRealPath());
        assertThat(listing.parentPath()).isEqualTo(root.toRealPath());
    }
}
