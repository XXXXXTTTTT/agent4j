package com.agent.sandbox.ast;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AstServiceDiffTest {

    private static final String SOURCE = """
            package example;
            public class Sample {
                int value() {
                    return 1;
                }
            }
            """;

    private static final String VALID_DIFF = """
            diff --git a/src/main/java/example/Sample.java b/src/main/java/example/Sample.java
            --- a/src/main/java/example/Sample.java
            +++ b/src/main/java/example/Sample.java
            @@ -1,6 +1,6 @@
             package example;
             public class Sample {
                 int value() {
            -        return 1;
            +        return 2;
                 }
             }
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesUnifiedDiffAndReturnsUpdatedFiles() throws Exception {
        Path repositoryRoot = initializeRepository("valid");
        Path sourceFile = writeSource(repositoryRoot);

        List<Path> updatedFiles = new AstService().applyDiff(repositoryRoot, VALID_DIFF);

        assertThat(Files.readString(sourceFile, StandardCharsets.UTF_8))
                .contains("return 2;")
                .doesNotContain("return 1;");
        assertThat(updatedFiles).containsExactly(sourceFile.toRealPath());
        assertThatThrownBy(() -> updatedFiles.add(sourceFile))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsConflictingPatchAndPreservesFile() throws Exception {
        Path repositoryRoot = initializeRepository("conflict");
        Path sourceFile = writeSource(repositoryRoot);
        AstService service = new AstService();
        service.applyDiff(repositoryRoot, VALID_DIFF);

        assertThatThrownBy(() -> service.applyDiff(repositoryRoot, VALID_DIFF))
                .isInstanceOf(AstServiceException.class)
                .hasCauseInstanceOf(Exception.class);
        assertThat(Files.readString(sourceFile, StandardCharsets.UTF_8))
                .contains("return 2;");
    }

    @Test
    void rejectsEmptyPatchAndNonRepository() throws IOException {
        Path plainDirectory = Files.createDirectory(temporaryDirectory.resolve("plain"));
        AstService service = new AstService();

        assertThatThrownBy(() -> service.applyDiff(plainDirectory, " "))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("补丁");
        assertThatThrownBy(() -> service.applyDiff(plainDirectory, VALID_DIFF))
                .isInstanceOf(AstServiceException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void validatesRepositoryBeforeParsingNonEmptyPatch() throws IOException {
        Path plainDirectory = Files.createDirectory(temporaryDirectory.resolve("invalid-patch"));

        assertThatThrownBy(() -> new AstService().applyDiff(plainDirectory, "not a diff"))
                .isInstanceOf(AstServiceException.class)
                .hasCauseInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void rejectsPathTraversalBeforeWritingOutsideRepository() throws Exception {
        Path repositoryRoot = initializeRepository("traversal");
        Path outsideFile = repositoryRoot.getParent().resolve("outside.txt");
        String traversalDiff = """
                diff --git a/../outside.txt b/../outside.txt
                new file mode 100644
                --- /dev/null
                +++ b/../outside.txt
                @@ -0,0 +1 @@
                +outside
                """;

        assertThatThrownBy(() -> new AstService().applyDiff(repositoryRoot, traversalDiff))
                .isInstanceOf(AstServiceException.class)
                .hasMessageContaining("工作树");
        assertThat(outsideFile).doesNotExist();
    }

    private Path initializeRepository(String directoryName) throws Exception {
        Path repositoryRoot = Files.createDirectory(temporaryDirectory.resolve(directoryName));
        try (Git ignored = Git.init().setDirectory(repositoryRoot.toFile()).call()) {
            return repositoryRoot;
        }
    }

    private Path writeSource(Path repositoryRoot) throws IOException {
        Path sourceFile = repositoryRoot.resolve("src/main/java/example/Sample.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, SOURCE, StandardCharsets.UTF_8);
        return sourceFile;
    }
}
